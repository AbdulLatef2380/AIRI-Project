plugins {
    id("com.android.test")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.airi.assistant.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions.managedDevices {
        devices {
            create<com.android.build.api.dsl.ManagedVirtualDevice>("pixel2api30") {
                device = "Pixel 2"
                apiLevel = 30
                systemImageSource = "aosp"
            }
        }
    }
}

baselineProfile {
    targetProjectPath = ":app"
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.4")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
