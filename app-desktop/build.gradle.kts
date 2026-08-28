import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.materialIconsExtended)
    implementation(project(":platform-macos"))
    implementation("fc:FC-JDK:1.0-SNAPSHOT")

    // Voyager — stack-based navigation for Compose Desktop
    implementation("cafe.adriel.voyager:voyager-navigator:1.1.0-beta02")
    implementation("cafe.adriel.voyager:voyager-screenmodel:1.1.0-beta02")
    implementation("cafe.adriel.voyager:voyager-transitions:1.1.0-beta02")

    // Compose Desktop runs on the AWT/Swing event thread. Without this
    // module, Dispatchers.Main and everything derived from it throws
    // "Module with the Main dispatcher is missing" at runtime.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
}

compose.desktop {
    application {
        mainClass = "com.fc.safe.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)

            // jlink strips the runtime image to what it can see statically, and it
            // cannot see reflective/service loads: logback pulls in javax.naming and
            // sqlite-jdbc pulls in java.sql. Without these the packaged app dies at
            // startup with "Failed to launch JVM" — which never reproduces under
            // `gradlew run`, since that uses the full local JDK. From
            // `gradlew :app-desktop:suggestRuntimeModules`.
            modules(
                "java.compiler",
                "java.instrument",
                "java.management",
                "java.naming",
                "java.security.jgss",
                "java.sql",
                "jdk.unsupported",
            )

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

// Phase 0 smoke test: FC-JDK key-gen + sign + verify on the combined classpath.
tasks.register<JavaExec>("smokeTest") {
    group = "verification"
    description = "Run FC-JDK crypto smoke test with Compose deps on classpath"
    mainClass.set("com.fc.safe.desktop.SmokeTestKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// Phase 1 smoke test: DesktopApp lifecycle + EasyDB round-trip.
tasks.register<JavaExec>("phase1Smoke") {
    group = "verification"
    description = "Open DB context, write/read FcEntity, close, reopen, verify"
    mainClass.set("com.fc.safe.desktop.Phase1SmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// Phase 2 smoke test: AvatarMaker layer loading + composite.
tasks.register<JavaExec>("avatarSmoke") {
    group = "verification"
    description = "Composite a few avatars and dump PNGs under build/avatar-smoke"
    mainClass.set("com.fc.safe.desktop.AvatarSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// Phase 2 backup interop smoke: base64 bundle + BackupCodec + password
// encrypt/decrypt symmetry (no WalletSession / no DB).
tasks.register<JavaExec>("backupInteropSmoke") {
    group = "verification"
    description = "Build + walk an Android-shaped export blob end to end"
    mainClass.set("com.fc.safe.desktop.BackupInteropSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// Phase 2 TOTP smoke: RFC 6238 Appendix B test vectors against TotpUtil.
tasks.register<JavaExec>("totpSmoke") {
    group = "verification"
    description = "Validate TOTP output against RFC 6238 Appendix B vectors"
    mainClass.set("com.fc.safe.desktop.TotpSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// Phase 2 secret-backup interop smoke: Android-shaped Secret export
// envelope + BackupCodec walk + CryptoDataStr decrypt round-trip.
tasks.register<JavaExec>("secretBackupInteropSmoke") {
    group = "verification"
    description = "Round-trip a secret through the Android-shaped export envelope"
    mainClass.set("com.fc.safe.desktop.SecretBackupInteropSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
}
