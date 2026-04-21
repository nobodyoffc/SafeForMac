import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    id("org.jetbrains.compose") version "1.7.3"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "spike.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "SafeSpikeCompose"
            packageVersion = "1.0.0"
            macOS {
                bundleID = "com.fc.safe.spike.compose"
                signing {
                    sign.set(true)
                    identity.set("CHANGYONG LIU (5768V787GP)")
                }
                notarization {
                    appleID.set(providers.environmentVariable("APPLE_ID"))
                    password.set(providers.environmentVariable("APPLE_APP_SPECIFIC_PASSWORD"))
                    teamID.set("5768V787GP")
                }
            }
        }
    }
}
