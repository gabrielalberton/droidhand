package work.alberton.droidhand.unlock

/** How keys of the lock screen's PIN pad are recognized from their accessibility labels (pure, unit-tested). */
object PinPad {
    /** The digit a key stands for: "1", or "2 ABC" / "2ABC" style. Null for numbers and times ("10", "10:42") and other text. */
    fun digitOf(label: String): Char? {
        val l = label.trim()
        val d = l.firstOrNull() ?: return null
        if (!d.isDigit()) return null
        if (l.length > 1 && (l[1].isDigit() || l[1] == ':')) return null
        return d
    }

    private val ENTER_LABELS = setOf("ok", "enter", "confirmar", "concluído", "done")

    /** The OK / Enter key, by resource id or label. */
    fun isEnter(label: String, viewId: String): Boolean {
        val id = viewId.lowercase()
        return id.endsWith("key_enter") || id.contains("ok_button") || label.trim().lowercase() in ENTER_LABELS
    }
}
