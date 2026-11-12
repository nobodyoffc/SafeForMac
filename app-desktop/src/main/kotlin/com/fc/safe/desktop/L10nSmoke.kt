package com.fc.safe.desktop

import com.fc.safe.desktop.i18n.L10n
import com.fc.safe.desktop.i18n.tr
import java.io.File
import kotlin.system.exitProcess

/**
 * Keeps the Chinese table and the code in step: every `tr("…")` in the
 * sources has a Chinese line, with as many `%s` as the English, and the table
 * holds nothing the code no longer says.
 *
 * Run: `./gradlew :app-desktop:l10nSmoke`
 */
fun main(args: Array<String>) {
    val sources = File(args[0])
    val call = Regex("""\btr\(\s*"((?:[^"\\]|\\.)*)"""")
    val used = HashSet<String>()
    sources.walkTopDown().filter { it.extension == "kt" && it.name != "L10nSmoke.kt" }.forEach { file ->
        call.findAll(file.readText()).forEach { used += unescape(it.groupValues[1]) }
    }
    val zh = L10n.load("/i18n/zh.txt")
    val placeholder = Regex("%(?:\\d+\\$)?s")
    fun count(s: String) = placeholder.findAll(s).count()

    val missing = used.filter { it !in zh }.sorted()
    val stale = zh.keys.filter { it !in used }.sorted()
    val uneven = zh.filter { (en, cn) -> count(en) != count(cn) }.keys.sorted()

    missing.forEach { println("NO CHINESE   $it") }
    stale.forEach { println("NOT IN CODE  $it") }
    uneven.forEach { println("%s MISMATCH  $it") }

    val filled = L10n.fill("%2\$s 个成员中任意 %1\$s 个", arrayOf(2, 3)) == "3 个成员中任意 2 个" &&
        L10n.fill("%s / %s", arrayOf("a", "b")) == "a / b" && L10n.fill("100%", emptyArray()) == "100%"
    if (!filled) println("FAIL  placeholder filling")
    println("${used.size} strings in code, ${zh.size} in zh.txt; tr(\"Save\") here is \"${tr("Save")}\"")

    if (missing.isNotEmpty() || stale.isNotEmpty() || uneven.isNotEmpty() || !filled) exitProcess(1)
    println("All checks passed")
}

private fun unescape(s: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        if (s[i] == '\\' && i + 1 < s.length) {
            out.append(when (s[i + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i + 1] })
            i += 2
        } else out.append(s[i++])
    }
    return out.toString()
}
