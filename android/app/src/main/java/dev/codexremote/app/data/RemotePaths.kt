package dev.codexremote.app.data

import java.net.URI

/** Remote paths must not inherit Android's POSIX-only java.io.File semantics. */
internal fun isRemoteAbsolutePath(path: String): Boolean {
    if (path.isEmpty() || '\u0000' in path) return false
    val normalized = path.replace('\\', '/')
    return when {
        normalized.startsWith("//") -> Regex("^//[^/]+/[^/]+(?:/.*)?$").matches(normalized)
        path.startsWith('/') -> true
        else -> Regex("^[A-Za-z]:[/\\\\].*$").matches(path)
    }
}

private fun normalizedRemotePath(path: String): String {
    require(isRemoteAbsolutePath(path)) { "必须为远端绝对路径" }
    return if (path.startsWith("\\\\") || path.startsWith("//") || path.getOrNull(1) == ':') path.replace('\\', '/') else path
}

internal fun remotePathRoot(path: String): String {
    val normalized = normalizedRemotePath(path)
    return when {
        normalized.startsWith("//") -> "//" + normalized.drop(2).split('/').take(2).joinToString("/") + "/"
        normalized.getOrNull(1) == ':' -> normalized.take(3)
        else -> "/"
    }
}

internal fun remotePathParent(path: String): String {
    val normalized = normalizedRemotePath(path).trimEnd('/')
    val root = remotePathRoot(path)
    if (normalized.length <= root.length) return root
    return normalized.substringBeforeLast('/').takeIf { it.length >= root.length } ?: root
}

internal fun remotePathName(path: String): String {
    val normalized = normalizedRemotePath(path).trimEnd('/')
    return normalized.substringAfterLast('/').ifEmpty { remotePathRoot(path) }
}

internal fun joinRemotePath(parent: String, name: String): String {
    require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }) { "名称不能包含路径分隔符" }
    return normalizedRemotePath(parent).trimEnd('/') + "/" + name
}

internal fun remotePathBreadcrumbs(path: String): List<Pair<String, String>> {
    val normalized = normalizedRemotePath(path)
    val root = remotePathRoot(path)
    val segments = normalized.drop(root.trimEnd('/').length).split('/').filter(String::isNotEmpty)
    return listOf(root to root) + segments.mapIndexed { index, name ->
        name to (root.trimEnd('/') + "/" + segments.take(index + 1).joinToString("/"))
    }
}

internal fun remotePathToFileUri(path: String): String {
    val normalized = normalizedRemotePath(path)
    val authority = if (normalized.startsWith("//")) normalized.drop(2).substringBefore('/') else ""
    val uriPath = when {
        authority.isNotEmpty() -> normalized.drop(2 + authority.length)
        normalized.getOrNull(1) == ':' -> "/$normalized"
        else -> normalized
    }
    return URI("file", authority, uriPath, null).toASCIIString()
}

internal fun remoteFileUriToPath(value: String): String? = runCatching {
    val uri = URI(value)
    require(uri.scheme == "file" && uri.query == null && uri.fragment == null)
    val path = uri.path ?: return null
    val remotePath = when {
        !uri.authority.isNullOrEmpty() -> "//${uri.authority}$path"
        Regex("^/[A-Za-z]:/.*$").matches(path) -> path.drop(1)
        else -> path
    }
    remotePath.takeIf(::isRemoteAbsolutePath)
}.getOrNull()
