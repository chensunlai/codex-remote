package dev.codexremote.app.data

/** Main-thread handoff: management pauses a window before sending its stop request. */
internal class WindowConnectionRegistry {
    private val connections = mutableMapOf<String, (Boolean) -> Unit>()
    private val suspended = mutableSetOf<String>()

    fun register(id: String, callback: (Boolean) -> Unit): () -> Unit {
        connections[id] = callback
        callback(id in suspended)
        return { if (connections[id] === callback) connections.remove(id) }
    }

    fun setSuspended(id: String, value: Boolean) {
        if (value) suspended.add(id) else suspended.remove(id)
        connections[id]?.invoke(value)
    }

    fun invalidate(id: String) { connections.remove(id) }

    fun clear() {
        connections.values.toList().forEach { it(true) }
        connections.clear()
        suspended.clear()
    }
}
