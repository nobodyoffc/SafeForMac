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
    implementation("fc:FC-JDK:1.0-SNAPSHOT")
}

compose.desktop {
    application {
        mainClass = "com.fc.safe.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Safe"
            packageVersion = "1.0.0"
            macOS {
                bundleID = "com.fc.safe.desktop"
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

// Smoke test: FC-JDK key-gen + sign + verify on the combined classpath.
// Verifies that FC-JDK's flat packages (utils, core, etc.) don't collide
// with Compose/Kotlin transitives when both are on the same classpath.
tasks.register<JavaExec>("smokeTest") {
    group = "verification"
    description = "Run FC-JDK crypto smoke test with Compose deps on classpath"
    mainClass.set("com.fc.safe.desktop.SmokeTestKt")
    classpath = sourceSets["main"].runtimeClasspath
}
