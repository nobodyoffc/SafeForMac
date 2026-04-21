plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

application {
    mainClass.set("spike.Main")
}

javafx {
    version = "21.0.5"
    modules = listOf("javafx.controls")
}

val collectLibs = tasks.register<Copy>("collectLibs") {
    dependsOn("jar")
    from(configurations.runtimeClasspath)
    from(tasks.named<Jar>("jar"))
    into(layout.buildDirectory.dir("app-lib"))
}

tasks.register<Exec>("packageDmg") {
    group = "distribution"
    description = "Build a .dmg via jpackage"
    dependsOn(collectLibs)
    val outputDir = layout.buildDirectory.dir("jpackage")
    val inputDir = layout.buildDirectory.dir("app-lib")
    val mainJarName = tasks.named<Jar>("jar").flatMap { it.archiveFileName }
    doFirst {
        val out = outputDir.get().asFile
        out.mkdirs()
        // jpackage refuses to write into a directory that already contains the same-named artifact
        out.listFiles()?.forEach { it.delete() }
    }
    commandLine(
        "jpackage",
        "--type", "dmg",
        "--name", "SafeSpikeJavaFX",
        "--app-version", "1.0.0",
        "--main-jar", mainJarName.get(),
        "--main-class", "spike.Main",
        "--input", inputDir.get().asFile.absolutePath,
        "--dest", outputDir.get().asFile.absolutePath,
        "--mac-package-identifier", "com.fc.safe.spike.javafx",
        "--mac-sign",
        "--mac-signing-key-user-name", "CHANGYONG LIU (5768V787GP)"
    )
}
