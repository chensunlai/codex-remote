package dev.codexremote.app.data

import dev.codexremote.app.model.WorkspaceWindow

/** A suggestion stored as an ordinary title; user names need not be unique. */
internal fun defaultWorkspaceWindowTitle(
    windows: List<WorkspaceWindow>, serviceId: String, toolId: String, serviceName: String,
): String {
    val matching = windows.filter { it.serviceId == serviceId && it.toolId == toolId }
    if (matching.isEmpty()) return serviceName
    val names = matching.mapTo(mutableSetOf()) { it.title }
    var number = 2
    while ("$serviceName $number" in names) number++
    return "$serviceName $number"
}

/** Most recently selected first; the visible window order remains unchanged. */
internal fun recentWorkspaceWindowIds(
    windows: List<WorkspaceWindow>, recentIds: List<String>, selectedId: String?,
): List<String> {
    val valid = windows.mapTo(mutableSetOf()) { it.id }
    return (listOfNotNull(selectedId) + recentIds + windows.asReversed().map { it.id })
        .filter { it in valid }.distinct()
}

internal fun reusableWorkspaceWindow(
    windows: List<WorkspaceWindow>, recentIds: List<String>, selectedId: String?,
    serviceId: String, toolId: String? = null,
): WorkspaceWindow? {
    val matching = windows.filter { it.serviceId == serviceId && (toolId == null || it.toolId == toolId) }
        .associateBy { it.id }
    return recentWorkspaceWindowIds(windows, recentIds, selectedId).firstNotNullOfOrNull(matching::get)
}
