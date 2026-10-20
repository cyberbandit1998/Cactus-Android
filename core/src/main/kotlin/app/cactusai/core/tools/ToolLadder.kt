package app.cactusai.core.tools

/**
 * The order in which tool lists are offered to `needle_init`, which fails when the list does not fit the model's context
 * window (a size that can only be learned on the phone). Wording gets shorter first; then whole groups are given up in
 * the order of least harm: optional screen control, then online questions, then local information. The phone actions the
 * app was first built around are never dropped.
 */
object ToolLadder {
    private val dropOrder = listOf(ToolGroup.SCREEN, ToolGroup.ONLINE, ToolGroup.INFO)

    fun steps(groups: Set<ToolGroup>): List<Pair<Set<ToolGroup>, ToolDetail>> = buildList {
        ToolDetail.entries.forEach { add(groups to it) }
        var current = groups
        for (drop in dropOrder) {
            if (drop in current) {
                current = current - drop
                add(current to ToolDetail.MINIMAL)
            }
        }
    }
}
