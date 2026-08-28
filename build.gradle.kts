// Root build file. Declares plugin versions with `apply false` so
// subprojects can pull them in by ID without re-declaring the version —
// the alternative (version in both :app-desktop and :platform-macos)
// makes Gradle load the Kotlin plugin twice and print the
// "Kotlin Gradle plugin was loaded multiple times" warning.
plugins {
    kotlin("jvm") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
}
