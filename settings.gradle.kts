pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Drop the Newland NDK .aar/.jar into app/libs/ and the flatDir
        // repository below picks it up. Replace with their Maven URL if
        // they ever publish one.
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "HDQ POS"
include(":app")
