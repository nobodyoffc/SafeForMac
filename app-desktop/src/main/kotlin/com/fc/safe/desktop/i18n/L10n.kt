package com.fc.safe.desktop.i18n

import java.util.Locale

/**
 * The app's text in the language macOS is set to: Chinese when the system
 * language is Chinese, English otherwise.
 *
 * English is written in the code and is its own key, gettext-style:
 * `tr("Save")`, or `tr("%s selected", n)` where the text has values in it.
 * The Chinese for each key is in `resources/i18n/zh.txt`. A key with no
 * Chinese shows in English; `l10nSmoke` lists any such key.
 *
 * Only text the app itself writes goes through [tr]. What the user stored —
 * labels, memos, carvings — is shown as it is.
 */
object L10n {

    /** `-Dsafe.lang=zh` or `en` overrides the system language, for testing. */
    val chinese: Boolean =
        (System.getProperty("safe.lang") ?: Locale.getDefault().language).lowercase().startsWith("zh")

    val zh: Map<String, String> by lazy { load("/i18n/zh.txt") }

    /**
     * The table file: an English line, then its Chinese line, then a blank line.
     * `\n` stands for a line break; leading and trailing spaces count.
     */
    internal fun load(resource: String): Map<String, String> {
        val text = L10n::class.java.getResourceAsStream(resource)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return emptyMap()
        val table = HashMap<String, String>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            if (lines[i].isEmpty() || lines[i].startsWith("# ")) { i++; continue }
            val key = lines[i]
            val value = lines.getOrNull(i + 1).orEmpty()
            require(value.isNotEmpty()) { "$resource line ${i + 1}: no translation under \"$key\"" }
            table[key.replace("\\n", "\n")] = value.replace("\\n", "\n")
            i += 2
        }
        return table
    }

    /** Puts [args] where the `%s` (in order) or `%1$s` (by number) of [pattern] are. */
    internal fun fill(pattern: String, args: Array<out Any?>): String {
        if (args.isEmpty()) return pattern
        var next = 0
        return PLACEHOLDER.replace(pattern) { m ->
            val index = m.groupValues[1].toIntOrNull()?.minus(1) ?: next++
            args.getOrNull(index).toString()
        }
    }

    private val PLACEHOLDER = Regex("%(?:(\\d+)\\$)?s")
}

/** [text] in the system language, with [args] in place of its `%s`. */
fun tr(text: String, vararg args: Any?): String {
    val pattern = if (L10n.chinese) L10n.zh[text] ?: text else text
    return L10n.fill(pattern, args)
}
