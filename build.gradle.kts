plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false

    // Google Services Plugin (Firebase)
    id("com.google.gms.google-services") version "4.4.1" apply false

    // Firebase Crashlytics Plugin
    id("com.google.firebase.crashlytics") version "2.9.9" apply false
}
