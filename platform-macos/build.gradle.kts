plugins {
    kotlin("jvm") version "2.1.20"
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    api("fc:FC-JDK:1.0-SNAPSHOT")
    api("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.16")
    implementation("ch.qos.logback:logback-core:1.5.16")
}
