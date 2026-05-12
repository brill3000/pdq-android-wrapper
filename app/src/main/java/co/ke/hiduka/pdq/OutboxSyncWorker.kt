package co.ke.hiduka.pdq

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Background outbox replay. Reads queued mutations from native SQLite
 * and POSTs them to the GraphQL endpoint with the cached JWT. Runs
 * under WorkManager's CONNECTED constraint + 15-min periodic schedule,
 * so a cashier who rings up sales offline gets them synced even with
 * the PWA closed.
 *
 * Coupling notes (worth removing in a follow-up):
 *   * Worker knows the mutation text for each `OutboxKind` it supports —
 *     today that's just CREATE_SALE_INVOICE. New kinds need a matching
 *     entry in [Mutations].
 *   * GraphQL endpoint comes from kv("graphqlEndpoint") set by the PWA
 *     on boot, falling back to [BuildConfig.GRAPHQL_ENDPOINT].
 *
 * Result semantics:
 *   * Result.success() — queue is empty / fully drained / no auth.
 *   * Result.retry()   — transient network or server failure. WorkManager
 *     backs off and tries again.
 *   * Result.failure() — JWT rejected (401). User must log back in
 *     before the queue can replay. Don't keep hammering.
 */
class OutboxSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val storage = Storage.get(applicationContext)
        val token = storage.kvGet("accessToken")
        if (token.isNullOrBlank()) {
            WrapperLogger.i(TAG, "skip — no cached JWT", mapOf("queue" to queueSize(storage)))
            return@withContext Result.success()
        }

        val endpoint = storage.kvGet("graphqlEndpoint")
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.GRAPHQL_ENDPOINT
        val deviceId = storage.kvGet("deviceId")

        val entries = try {
            JSONArray(storage.outboxList())
        } catch (e: Throwable) {
            WrapperLogger.e(TAG, "could not read outbox: ${e.message}", e)
            return@withContext Result.success()
        }

        if (entries.length() == 0) return@withContext Result.success()

        WrapperLogger.i(
            TAG,
            "starting replay",
            mapOf("queue" to entries.length(), "endpoint" to endpoint),
        )

        var any401 = false
        var anyRetryable = false
        val limit = minOf(entries.length(), BATCH_LIMIT)

        for (i in 0 until limit) {
            val entry = entries.getJSONObject(i)
            val id = entry.getString("id")
            val kind = entry.getString("kind")
            val attempts = entry.optInt("attempts", 0)
            val variables = entry.optJSONObject("variables") ?: JSONObject()

            val mutationText = Mutations.byKind(kind)
            if (mutationText == null) {
                WrapperLogger.w(TAG, "unknown kind, skipping", mapOf("id" to id, "kind" to kind))
                continue
            }
            val operationName = Mutations.operationName(kind)

            if (attempts >= MAX_ATTEMPTS) {
                WrapperLogger.e(
                    TAG,
                    "dropping poison entry after $MAX_ATTEMPTS attempts",
                    Exception("kind=$kind id=$id"),
                )
                storage.outboxRemove(id)
                continue
            }

            val result = postGraphQL(endpoint, token, deviceId, operationName, mutationText, variables)
            when (result) {
                is GqlResult.Ok -> {
                    storage.outboxRemove(id)
                    WrapperLogger.i(TAG, "replayed", mapOf("id" to id, "kind" to kind))
                }
                is GqlResult.Unauthorized -> {
                    any401 = true
                    break
                }
                is GqlResult.ServerError -> {
                    storage.outboxMarkAttempt(id, result.message)
                    anyRetryable = true
                    WrapperLogger.w(
                        TAG,
                        "transient failure",
                        mapOf("id" to id, "error" to result.message, "attempts" to attempts + 1),
                    )
                }
                is GqlResult.GraphQLError -> {
                    storage.outboxMarkAttempt(id, result.message)
                    anyRetryable = true
                    WrapperLogger.w(
                        TAG,
                        "server rejected",
                        mapOf("id" to id, "error" to result.message, "attempts" to attempts + 1),
                    )
                }
            }
        }

        return@withContext when {
            any401 -> {
                WrapperLogger.w(TAG, "JWT rejected — replay stopped until next login")
                Result.failure()
            }
            anyRetryable -> Result.retry()
            else -> Result.success()
        }
    }

    private fun queueSize(storage: Storage): Int =
        try {
            JSONArray(storage.outboxList()).length()
        } catch (_: Throwable) {
            -1
        }

    private fun postGraphQL(
        endpoint: String,
        token: String,
        deviceId: String?,
        operationName: String,
        query: String,
        variables: JSONObject,
    ): GqlResult {
        val body = JSONObject().apply {
            put("query", query)
            put("variables", variables)
            put("operationName", operationName)
        }.toString()

        return try {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 20_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                // Apollo Server v4 CSRF protection blocks POSTs unless one
                // of these is set even when content-type is JSON. Apollo
                // Client attaches `x-apollo-operation-name` automatically;
                // raw HttpURLConnection does not, so we send both here.
                setRequestProperty("apollo-require-preflight", "true")
                setRequestProperty("x-apollo-operation-name", operationName)
                if (!deviceId.isNullOrBlank()) {
                    setRequestProperty("x-hdq-device-id", deviceId)
                }
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            if (status == 401 || status == 403) return GqlResult.Unauthorized
            val responseText = (
                if (status in 200..299) conn.inputStream else conn.errorStream
                )?.bufferedReader()?.use { it.readText() } ?: ""
            if (status >= 500) return GqlResult.ServerError("HTTP $status: ${responseText.take(200)}")

            val parsed = try {
                JSONObject(responseText)
            } catch (_: Throwable) {
                return GqlResult.ServerError("non-JSON response: ${responseText.take(200)}")
            }
            val errors = parsed.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val firstMsg = errors.optJSONObject(0)?.optString("message") ?: "unknown GraphQL error"
                val code = errors.optJSONObject(0)?.optJSONObject("extensions")?.optString("code")
                if (code == "UNAUTHENTICATED" || code == "UNAUTHORIZED") return GqlResult.Unauthorized
                return GqlResult.GraphQLError(firstMsg)
            }
            GqlResult.Ok
        } catch (e: Throwable) {
            GqlResult.ServerError(e.message ?: e.javaClass.simpleName)
        }
    }

    private sealed class GqlResult {
        object Ok : GqlResult()
        object Unauthorized : GqlResult()
        data class ServerError(val message: String) : GqlResult()
        data class GraphQLError(val message: String) : GqlResult()
    }

    companion object {
        private const val TAG = "OutboxSyncWorker"
        private const val BATCH_LIMIT = 20
        private const val MAX_ATTEMPTS = 5
    }
}

/**
 * Mutation registry. The worker can't pull these from the PWA at
 * runtime (background workers run without the WebView) so any new
 * outbox kind needs an entry here. Mirror the document with whatever's
 * in `react-pos/src/api/<resource>.ts` — only the fields the server
 * needs to accept the mutation are required; we don't need the full
 * response selection set since the worker doesn't consume the result.
 */
private object Mutations {
    private const val CREATE_SALE_INVOICE_OP = "CreateInvoice"
    private const val CREATE_SALE_INVOICE_QUERY = """
        mutation CreateInvoice(
          ${'$'}sent: Float!
          ${'$'}taxes: Float!
          ${'$'}status: String!
          ${'$'}subTotal: Float!
          ${'$'}discount: Float!
          ${'$'}shipping: Float!
          ${'$'}totalAmount: Float!
          ${'$'}taxAmount: Float!
          ${'$'}taxableAmount: Float!
          ${'$'}totalTaxableA: Float!
          ${'$'}totalTaxableB: Float!
          ${'$'}totalTaxableC: Float!
          ${'$'}totalTaxableD: Float!
          ${'$'}totalTaxableE: Float!
          ${'$'}totalTaxA: Float!
          ${'$'}totalTaxB: Float!
          ${'$'}totalTaxC: Float!
          ${'$'}totalTaxD: Float!
          ${'$'}totalTaxE: Float!
          ${'$'}items: [InvoiceItemInput!]!
          ${'$'}invoiceTo: String!
          ${'$'}externalReference: String
        ) {
          createSaleInvoice(
            createSaleInvoiceInput: {
              sent: ${'$'}sent
              taxes: ${'$'}taxes
              status: ${'$'}status
              subTotal: ${'$'}subTotal
              totalTaxableA: ${'$'}totalTaxableA
              totalTaxableB: ${'$'}totalTaxableB
              totalTaxableC: ${'$'}totalTaxableC
              totalTaxableD: ${'$'}totalTaxableD
              totalTaxableE: ${'$'}totalTaxableE
              totalTaxA: ${'$'}totalTaxA
              totalTaxB: ${'$'}totalTaxB
              totalTaxC: ${'$'}totalTaxC
              totalTaxD: ${'$'}totalTaxD
              totalTaxE: ${'$'}totalTaxE
              taxAmount: ${'$'}taxAmount
              taxableAmount: ${'$'}taxableAmount
              totalAmount: ${'$'}totalAmount
              discount: ${'$'}discount
              shipping: ${'$'}shipping
              items: ${'$'}items
              invoiceTo: ${'$'}invoiceTo
              externalReference: ${'$'}externalReference
            }
          ) { id invoiceNumber externalReference }
        }
    """

    private const val OPEN_SHIFT_OP = "OpenShift"
    private const val OPEN_SHIFT_QUERY = """
        mutation OpenShift(${'$'}openingCash: Float!, ${'$'}clientReference: String) {
          openShift(openingCash: ${'$'}openingCash, clientReference: ${'$'}clientReference) {
            id status openedAt openingCash
          }
        }
    """

    private const val CLOSE_SHIFT_OP = "CloseShift"
    private const val CLOSE_SHIFT_QUERY = """
        mutation CloseShift(
          ${'$'}shiftId: String!
          ${'$'}closingCash: Float!
          ${'$'}closingNote: String
          ${'$'}clientReference: String
        ) {
          closeShift(
            shiftId: ${'$'}shiftId
            closingCash: ${'$'}closingCash
            closingNote: ${'$'}closingNote
            clientReference: ${'$'}clientReference
          ) {
            id status closedAt closingCash
          }
        }
    """

    private const val CREATE_CUSTOMER_OP = "CreateCustomer"
    private const val CREATE_CUSTOMER_QUERY = """
        mutation CreateCustomer(
          ${'$'}custTin: String
          ${'$'}custNm: String!
          ${'$'}adrs: String
          ${'$'}telNo: String!
          ${'$'}email: String
          ${'$'}fullAddress: String
          ${'$'}useYn: String!
          ${'$'}isWholesale: Boolean
          ${'$'}clientReference: String
        ) {
          createBranchCustomer(
            createCustomerInput: {
              custTin: ${'$'}custTin
              custNm: ${'$'}custNm
              adrs: ${'$'}adrs
              telNo: ${'$'}telNo
              email: ${'$'}email
              fullAddress: ${'$'}fullAddress
              useYn: ${'$'}useYn
              isWholesale: ${'$'}isWholesale
              clientReference: ${'$'}clientReference
            }
          ) { id custNm telNo }
        }
    """

    fun byKind(kind: String): String? = when (kind) {
        "CREATE_SALE_INVOICE" -> CREATE_SALE_INVOICE_QUERY
        "OPEN_SHIFT" -> OPEN_SHIFT_QUERY
        "CLOSE_SHIFT" -> CLOSE_SHIFT_QUERY
        "CREATE_CUSTOMER" -> CREATE_CUSTOMER_QUERY
        else -> null
    }

    fun operationName(kind: String): String = when (kind) {
        "CREATE_SALE_INVOICE" -> CREATE_SALE_INVOICE_OP
        "OPEN_SHIFT" -> OPEN_SHIFT_OP
        "CLOSE_SHIFT" -> CLOSE_SHIFT_OP
        "CREATE_CUSTOMER" -> CREATE_CUSTOMER_OP
        else -> kind
    }
}
