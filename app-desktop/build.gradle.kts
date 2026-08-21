import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-domain"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.airi.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "AIRI"
            packageVersion = "1.0.0"
            description = "AIRI desktop foundation"
            vendor = "AIRI"

            linux {
                debMaintainer = "maintainers@airi.local"
                menuGroup = "Utility"
                appCategory = "Utility"
            }
        }
    }
}
