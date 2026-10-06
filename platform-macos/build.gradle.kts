plugins {
    kotlin("jvm")
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    api("com.github.nobodyoffc.Freeverse:FC-JDK:v0.2")
    api("org.slf4j:slf4j-api:2.0.16")
    // `api` so app-desktop can collect/observe `WalletSession.isLockedFlow`
    // and `LockManager.events` without re-declaring the dependency.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("ch.qos.logback:logback-classic:1.5.16")
    implementation("ch.qos.logback:logback-core:1.5.16")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    // ScreenCaptureGuard: sets NSWindow.sharingType through the Objective-C runtime.
    implementation("net.java.dev.jna:jna:5.15.0")
}

// Legacy-wallet → data-key vault migration, crash/resume and password
// rules, end to end. Runs against a throwaway home under build/: it
// creates and deletes wallets, so it must never see the real one.
tasks.register<JavaExec>("vaultMigrationSmoke") {
    group = "verification"
    description = "Migrate, crash-resume and re-key wallets in a throwaway home"
    mainClass.set("com.fc.safe.platform.macos.VaultMigrationSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
    val home = layout.buildDirectory.dir("vault-smoke-home")
    doFirst { delete(home) }
    systemProperty("user.home", home.get().asFile.path)
}
