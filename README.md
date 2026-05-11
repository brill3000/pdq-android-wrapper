# HDQ POS — Android wrapper (Newland NB60)

Tiny Android shell around `https://pdq.hiduka.co.ke`. Its only reason for existing is to give the PWA a JavaScript bridge to the NB60's built-in thermal printer, which Web APIs can't reach.

## What's in here

- `MainActivity` — full-bleed WebView that loads the production PDQ URL (configurable per build via `BuildConfig.WEB_APP_URL`).
- `PrinterBridge` — `@JavascriptInterface` exposed to JS as `window.HidukaPrinter`. Methods: `isAvailable()`, `printText()`, `printQrCode()`, `printEscPos()`, `cutPaper()`, `getDeviceInfo()`. All return a JSON string `{ok, error?}`.
- `NewlandPrinter` — **stubbed today.** Drop the Newland NDK `.aar` into `app/libs/` and replace the stubs with real SDK calls. The bridge contract on the JS side does not change.

## Building

You need Android Studio (Hedgehog or newer) or the Android SDK CLI:

```bash
# First time only — generates gradlew + gradle/wrapper/gradle-wrapper.jar:
gradle wrapper --gradle-version 8.5

# Then:
./gradlew assembleDebug         # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease       # signed with debug key for now
```

Install on a connected NB60:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Adding the Newland NDK SDK

1. Get the SDK `.aar` from Newland (typically `nlsdk-aidl-<version>.aar`).
2. Copy it to `app/libs/`.
3. Uncomment the `implementation(files("libs/..."))` line in `app/build.gradle.kts`.
4. Open `app/src/main/java/co/ke/hiduka/pdq/NewlandPrinter.kt` and replace each stub method with the real SDK call. Sketch:

   ```kotlin
   private val sdkPrinter by lazy {
       DeviceServiceManager.getInstance().getPrinter()
   }
   
   fun isAvailable(): Boolean = runCatching { sdkPrinter != null }.getOrDefault(false)
   
   fun printText(text: String) {
       sdkPrinter.appendText(text, fontSize = 24, style = 0, align = 0)
       sdkPrinter.startPrint(/* callback */)
   }
   ```

The actual class/method names may differ between SDK versions — refer to Newland's NDK integration guide.

## JS-side usage

See `react-pos/src/hooks/use-native-printer.ts` (frontend repo) for the detection hook. Quick smoke test from the WebView devtools (`chrome://inspect`):

```js
JSON.parse(window.HidukaPrinter.getDeviceInfo())
window.HidukaPrinter.printText('hello from the PWA')
```

## Distribution

The release build today self-signs with the debug key — fine for sideloading, **not** fine for the Play Store. Before going to production:

1. Generate a release keystore: `keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias hdq`
2. Move the `signingConfig` from `debug` to a real release config in `app/build.gradle.kts`.
3. Don't commit the keystore.
