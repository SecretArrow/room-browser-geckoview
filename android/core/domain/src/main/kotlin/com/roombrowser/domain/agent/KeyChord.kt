package com.roombrowser.domain.agent

/**
 * A keyboard chord ("Control+Shift+k") split into the parts a KeyboardEvent
 * needs.
 *
 * WHY this is its own type: a model writing the key event by hand gets the
 * `keyCode` legacy fields wrong, and sites that still branch on them then
 * ignore the press. The mapping is a table, so it lives where a JVM test can
 * hold it.
 *
 * [parse] returns null for anything it does not know, so the caller can name
 * what it supports instead of dispatching a key that silently does nothing.
 *
 * KNOWN LIMIT: [key] is the unshifted layout key, and a modifier is reported
 * through the `*Key` flags rather than folded into it. A page reading
 * `event.key` therefore sees "k" where a real Shift+k would say "K" — the
 * chords that matter here (Escape, Tab, PageDown, Control+a) are unaffected.
 */
data class KeyChord(
    val key: String,
    val code: String,
    val keyCode: Int,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false
) {

    /** The chord as the user typed it, for tool results. */
    fun label(): String = buildString {
        if (ctrl) append("Control+")
        if (alt) append("Alt+")
        if (shift) append("Shift+")
        if (meta) append("Meta+")
        append(if (key == " ") "Space" else key)
    }

    companion object {

        /** What `press_keys` accepts, for the refusal message. */
        const val SUPPORTED_KEYS: String =
            "Enter, Escape, Tab, Space, Backspace, Delete, Insert, Home, End, PageUp, PageDown, " +
                "ArrowUp/ArrowDown/ArrowLeft/ArrowRight, F1-F12, a-z, 0-9, and , . / ; ' [ ] \\ - = `"

        fun parse(raw: String?): KeyChord? {
            val tokens = raw?.trim()?.split("+")?.map { it.trim() }?.filter { it.isNotEmpty() }
            if (tokens.isNullOrEmpty()) return null
            var ctrl = false
            var shift = false
            var alt = false
            var meta = false
            var keyToken: String? = null
            for (token in tokens) {
                when (token.lowercase()) {
                    "ctrl", "control" -> ctrl = true
                    "shift" -> shift = true
                    "alt", "option" -> alt = true
                    "meta", "cmd", "command", "win", "super" -> meta = true
                    else -> {
                        // Two key tokens is not a chord; refuse rather than guess.
                        if (keyToken != null) return null
                        keyToken = token
                    }
                }
            }
            val token = keyToken ?: return null
            val base = baseKey(token) ?: return null
            return KeyChord(base.key, base.code, base.keyCode, ctrl, shift, alt, meta)
        }

        private data class Base(val key: String, val code: String, val keyCode: Int)

        private val NAMED = mapOf(
            "enter" to Base("Enter", "Enter", 13),
            "return" to Base("Enter", "Enter", 13),
            "escape" to Base("Escape", "Escape", 27),
            "esc" to Base("Escape", "Escape", 27),
            "tab" to Base("Tab", "Tab", 9),
            "space" to Base(" ", "Space", 32),
            "spacebar" to Base(" ", "Space", 32),
            "backspace" to Base("Backspace", "Backspace", 8),
            "delete" to Base("Delete", "Delete", 46),
            "del" to Base("Delete", "Delete", 46),
            "insert" to Base("Insert", "Insert", 45),
            "home" to Base("Home", "Home", 36),
            "end" to Base("End", "End", 35),
            "pageup" to Base("PageUp", "PageUp", 33),
            "pagedown" to Base("PageDown", "PageDown", 34),
            "arrowup" to Base("ArrowUp", "ArrowUp", 38),
            "up" to Base("ArrowUp", "ArrowUp", 38),
            "arrowdown" to Base("ArrowDown", "ArrowDown", 40),
            "down" to Base("ArrowDown", "ArrowDown", 40),
            "arrowleft" to Base("ArrowLeft", "ArrowLeft", 37),
            "left" to Base("ArrowLeft", "ArrowLeft", 37),
            "arrowright" to Base("ArrowRight", "ArrowRight", 39),
            "right" to Base("ArrowRight", "ArrowRight", 39)
        )

        private val PUNCTUATION = mapOf(
            '-' to Base("-", "Minus", 189),
            '=' to Base("=", "Equal", 187),
            '[' to Base("[", "BracketLeft", 219),
            ']' to Base("]", "BracketRight", 221),
            '\\' to Base("\\", "Backslash", 220),
            ';' to Base(";", "Semicolon", 186),
            '\'' to Base("'", "Quote", 222),
            ',' to Base(",", "Comma", 188),
            '.' to Base(".", "Period", 190),
            '/' to Base("/", "Slash", 191),
            '`' to Base("`", "Backquote", 192)
        )

        private fun baseKey(token: String): Base? {
            val lower = token.lowercase()
            NAMED[lower]?.let { return it }
            if (lower.length == 1) {
                val c = lower[0]
                if (c in 'a'..'z') return Base(lower, "Key${c.uppercaseChar()}", c.uppercaseChar().code)
                if (c in '0'..'9') return Base(lower, "Digit$c", c.code)
                PUNCTUATION[c]?.let { return it }
            }
            if (lower.length in 2..3 && lower[0] == 'f') {
                val n = lower.drop(1).toIntOrNull() ?: return null
                if (n in 1..12) return Base("F$n", "F$n", 111 + n)
            }
            return null
        }
    }
}
