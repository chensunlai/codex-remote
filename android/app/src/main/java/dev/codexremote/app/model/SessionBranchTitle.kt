package dev.codexremote.app.model

private data class BranchTitleNode(
    val id: String,
    val forkedFromId: String?,
    val title: String,
)

private data class BranchLineage(val baseTitle: String, val rootId: String)
private data class ParsedBranchTitle(val baseTitle: String, val number: Int)

fun nextBranchTitle(source: SessionSummary, sessions: List<SessionSummary>): String? {
    val sourceNode = source.toBranchTitleNode()
    if (sourceNode.title.isEmpty()) return null
    val nodes = (sessions + source)
        .associate { it.id to it.toBranchTitleNode() }
        .toMutableMap()
        .apply { put(source.id, sourceNode) }
    val lineage = findBranchLineage(sourceNode, nodes)
    var highestNumber = 1
    nodes.values.forEach { node ->
        if (descendsFrom(node.id, lineage.rootId, nodes)) {
            highestNumber = maxOf(highestNumber, titleNumber(node.title, lineage.baseTitle) ?: 0)
        }
    }
    return formatBranchTitle(lineage.baseTitle, highestNumber + 1)
}

private fun SessionSummary.toBranchTitleNode() = BranchTitleNode(
    id = id,
    forkedFromId = forkedFromId,
    title = (name?.takeIf(String::isNotBlank) ?: preview).trim(),
)

private fun findBranchLineage(
    source: BranchTitleNode,
    nodes: Map<String, BranchTitleNode>,
): BranchLineage {
    val parsedSource = parseBranchTitle(source.title)
    if (parsedSource == null || source.forkedFromId == null) {
        return BranchLineage(source.title, source.id)
    }
    var parent: BranchTitleNode? = nodes[source.forkedFromId]
        ?: return BranchLineage(parsedSource.baseTitle, source.id)
    while (parent != null) {
        val parentTitle = parent.title.trim()
        val parsedParent = parseBranchTitle(parentTitle)
        val candidates = setOf(parentTitle, parsedParent?.baseTitle.orEmpty())
        for (candidate in candidates) {
            if (candidate.isEmpty() || formatBranchTitle(candidate, parsedSource.number) != source.title) {
                continue
            }
            var rootId = parent.id
            var ancestor = parent.forkedFromId?.let(nodes::get)
            while (ancestor != null && titleNumber(ancestor.title, candidate) != null) {
                rootId = ancestor.id
                ancestor = ancestor.forkedFromId?.let(nodes::get)
            }
            return BranchLineage(candidate, rootId)
        }
        parent = parent.forkedFromId?.let(nodes::get)
    }
    return BranchLineage(source.title, source.id)
}

private fun titleNumber(title: String, baseTitle: String): Int? {
    val normalized = title.trim()
    if (normalized == baseTitle) return 1
    val parsed = parseBranchTitle(normalized) ?: return null
    return parsed.number.takeIf { formatBranchTitle(baseTitle, it) == normalized }
}

private fun descendsFrom(
    nodeId: String,
    rootId: String,
    nodes: Map<String, BranchTitleNode>,
): Boolean {
    val visited = mutableSetOf<String>()
    var currentId: String? = nodeId
    while (currentId != null && visited.add(currentId)) {
        if (currentId == rootId) return true
        currentId = nodes[currentId]?.forkedFromId
    }
    return false
}

private fun parseBranchTitle(title: String): ParsedBranchTitle? {
    val match = BRANCH_SUFFIX.matchEntire(title) ?: return null
    val number = match.groupValues[2].toIntOrNull()?.takeIf { it >= 2 } ?: return null
    return ParsedBranchTitle(match.groupValues[1], number)
}

private fun formatBranchTitle(baseTitle: String, number: Int): String {
    val suffix = " ($number)"
    val available = MAX_BRANCH_TITLE_LENGTH - suffix.length
    val title = if (baseTitle.length > available) {
        baseTitle.take(available - 1).trimEnd() + "…"
    } else {
        baseTitle
    }
    return title + suffix
}

private val BRANCH_SUFFIX = Regex("^(.*) \\((\\d+)\\)$")
private const val MAX_BRANCH_TITLE_LENGTH = 60
