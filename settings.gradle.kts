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
    }
}

rootProject.name = "SleepTracker"

include(":core", ":audio", ":sleepanalysis")

// The Android app module needs an Android SDK. Pure-Kotlin modules can be built and
// tested on any JVM, so the app is skipped (with a warning) when no SDK is configured.
val localProps = file("local.properties")
val hasSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    (localProps.exists() && localProps.readText().contains("sdk.dir"))
if (hasSdk) {
    include(":app")
} else {
    logger.warn("No Android SDK found (ANDROID_HOME / local.properties) – building JVM modules only.")
}
