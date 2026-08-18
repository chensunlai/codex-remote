package dev.codexremote.app.data

internal class TerminalOutputBuffer(private val maxChars: Int) {
    private val content = StringBuilder()

    val value: String
        get() = content.toString()

    fun append(data: String) {
        if (data.isEmpty()) return
        content.append(data)
        val overflow = content.length - maxChars
        if (overflow <= 0) return
        val deleteThrough = if (
            overflow < content.length &&
            content[overflow].isLowSurrogate() &&
            content[overflow - 1].isHighSurrogate()
        ) {
            overflow + 1
        } else {
            overflow
        }
        content.delete(0, deleteThrough)
    }

    fun clear() {
        content.clear()
    }
}
