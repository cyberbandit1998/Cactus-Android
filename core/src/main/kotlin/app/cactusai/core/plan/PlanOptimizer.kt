package app.cactusai.core.plan

import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.ValidatedCall
import app.cactusai.core.web.SearchSite

object PlanOptimizer {
    /**
     * "Open YouTube and search for woodworking" comes back as two calls. Searching YouTube already opens
     * YouTube, so the first step is dropped instead of launching the app twice and racing the second launch.
     */
    fun collapse(steps: List<ValidatedCall>): List<ValidatedCall> {
        val out = ArrayList<ValidatedCall>(steps.size)
        for ((i, step) in steps.withIndex()) {
            val next = steps.getOrNull(i + 1)
            if (step.name == AssistantTools.OPEN_APP && next != null && next.name == AssistantTools.SEARCH_WEB) {
                val opened = step.args.string("app_name")?.let(SearchSite::fromSpokenName)
                val searched = SearchSite.fromKey(next.args.string("site"))
                if (opened != null && opened == searched) continue
            }
            if (out.lastOrNull() == step) continue
            out += step
        }
        return out
    }
}
