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
}
