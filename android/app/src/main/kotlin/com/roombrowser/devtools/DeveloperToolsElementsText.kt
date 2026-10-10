package com.roombrowser.devtools

/**
 * The Elements panel's own state, outside Compose.
 *
 * It is a plain object rather than a bundle of `remember`ed values because the
 * tree is written from coroutines: two expansions can overlap, and a write built
 * from a snapshot captured at tap time would drop the other one's children. All
 * of it is touched on the main thread, so a plain mutable map is enough -- and
 * the panel bumps a revision to force the recomposition, the same way the
 * console and network feeds do.
 */
internal class ElementsTreeState {

    var generation: Int? = null
    var root: ElementNode? = null
    var budgetExhausted: Boolean = false

    /** Children already fetched, per node id, in child order. */
    val children: MutableMap<Int, MutableList<ElementNode>> = mutableMapOf()

    /** How many element children the page said the node has, per node id. */
    val totals: MutableMap<Int, Int> = mutableMapOf()

    val expanded: MutableSet<Int> = mutableSetOf()

    var selected: Int? = null
    var detail: ElementDetail? = null

    /**
     * True when the page's registry was rebuilt under us.
     *
     * A refresh or a navigation makes every id this panel holds address a node
     * it no longer owns, so the panel says so and offers a re-read rather than
     * showing detail for whatever now answers to that number.
     */
    var stale: Boolean = false

    fun adopt(tree: ElementsTree?) {
        generation = tree?.gen
        root = tree?.node
        budgetExhausted = tree?.exhausted == true
        children.clear()
        totals.clear()
        expanded.clear()
        selected = null
        detail = null
        stale = false
    }

    /** The next child index to fetch for [id], which is also how many are already held. */
    fun nextFrom(id: Int): Int = children[id]?.size ?: 0

    fun addChildren(id: Int, page: ElementChildren) {
        val held = children.getOrPut(id) { mutableListOf() }
        page.nodes.orEmpty().forEach { node -> if (node.id != null) held += node }
        page.total?.let { totals[id] = it }
        if (page.exhausted == true) budgetExhausted = true
    }
}

/** One row of the visible tree: a node and how far it is indented. */
internal data class ElementTreeRow(val node: ElementNode, val depth: Int)

/**
 * The Elements panel as text.
 *
 * Rows are addressed by a CSS-ish selector rather than by their id, because an
 * id is this session's bookkeeping and a selector is something a reader can
 * paste into the page.
 */
internal object DeveloperToolsElementsText {

    /**
     * The panel's standing caveat, printed once above the tree.
     *
     * It is here because the tree shows ELEMENT nodes only: a page's text is a
     * child text node, and a tree that silently omitted them would look like a
     * page with no words in it. Where they are is stated instead.
     */
    const val TREE_CAPTION: String =
        "Element nodes only; each node's own text is counted in its detail. " +
            "The tree is built from the live document, so it matches what the page has now, " +
            "not what it was served."

    /** `<tag>#<id>.<classes>`, which is how a reader names a node out loud. */
    fun selector(node: ElementNode): String {
        val tag = node.tag.orEmpty().ifEmpty { "(no tag)" }
        val id = node.idAttr.orEmpty()
        val classes = node.classes.orEmpty()
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(".") { it }
        val idPart = if (id.isEmpty()) "" else "#$id"
        val classPart = if (classes.isEmpty()) "" else ".$classes"
        return "$tag$idPart$classPart"
    }

    /** What a row says about the node's contents, or null when it holds nothing. */
    fun childSummary(node: ElementNode): String? {
        val elements = node.children ?: return null
        val texts = node.childTextNodes
        val parts = mutableListOf<String>()
        if (elements > 0) parts += "$elements element${if (elements == 1) "" else "s"}"
        if (texts != null && texts > 0) parts += "$texts text node${if (texts == 1) "" else "s"}"
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }

    /**
     * The rows on screen, which are the root plus every expanded node's children.
     *
     * Walking only the expanded set is what bounds this: the cost is what the
     * reader opened, never the size of the document.
     */
    fun visibleRows(
        root: ElementNode?,
        children: Map<Int, List<ElementNode>>,
        expanded: Set<Int>
    ): List<ElementTreeRow> {
        if (root == null) return emptyList()
        val out = mutableListOf<ElementTreeRow>()
        fun walk(node: ElementNode, depth: Int) {
            out += ElementTreeRow(node, depth)
            val id = node.id ?: return
            if (id !in expanded) return
            children[id].orEmpty().forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out
    }

    /** What was read, and what its caps did, for the line under the tree. */
    fun treeSummary(state: ElementsTreeState, shown: Int): String {
        val rows = state.children.values.sumOf { it.size }
        val expanded = state.expanded.size
        val parts = mutableListOf("$shown row${if (shown == 1) "" else "s"} on screen")
        parts += "$rows child node${if (rows == 1) "" else "s"} read across $expanded expanded node${if (expanded == 1) "" else "s"}"
        val line = parts.joinToString(", ")
        return if (state.budgetExhausted) {
            "$line. The page has more nodes than this panel will hold " +
                "(${DeveloperToolsElementsScripts.NODE_BUDGET}), so some are not shown."
        } else {
            "$line."
        }
    }

    /** The whole visible tree, as one paste that keeps its indentation. */
    fun treeReport(rows: List<ElementTreeRow>, engineName: String): String {
        val header = "Elements -- engine: $engineName"
        if (rows.isEmpty()) return "$header\n(no tree was read)"
        val body = rows.joinToString("\n") { row -> "${"  ".repeat(row.depth)}${rowText(row.node)}" }
        return "$header\n$TREE_CAPTION\n\n$body"
    }

    /** One row of the tree, without its indentation. */
    fun rowText(node: ElementNode): String {
        val summary = childSummary(node)
        return if (summary == null) selector(node) else "${selector(node)} -- $summary"
    }

    /**
     * The selected node, as one paste.
     *
     * Every section names its own limit, so a capped value is never mistaken for
     * the whole of it -- and "the page did not answer" is its own line rather
     * than an empty section.
     */
    fun nodeDetailText(detail: ElementDetail?, engineName: String): String {
        val header = "Element -- engine: $engineName"
        if (detail == null) return "$header\nNo node is selected."
        if (detail.stale == true) {
            return "$header\nThe page's document was re-read, so this selection is no longer " +
                "valid. Read the tree again and pick the node from the new one."
        }
        val selector = buildString {
            append(detail.tag.orEmpty().ifEmpty { "(no tag)" })
            val id = detail.idAttr.orEmpty()
            if (id.isNotEmpty()) append("#$id")
            val classes = detail.classes.orEmpty().split(' ').filter { it.isNotBlank() }
            if (classes.isNotEmpty()) append("." + classes.joinToString("."))
        }
        val lines = mutableListOf(header, selector, "")
        if (detail.detached == true) {
            lines += "This node is no longer in the document, so it has no position on screen."
            lines += ""
        }

        val attributes = detail.attributes.orEmpty()
        lines += "Attributes (${attributes.size})"
        if (attributes.isEmpty()) {
            lines += "  (none)"
        } else {
            attributes.forEach { attribute ->
                lines += "  ${attribute.name.orEmpty()} = ${attribute.value.orEmpty()}"
            }
        }

        val elements = detail.childElements
        val texts = detail.childTextNodes
        lines += ""
        lines += "Children"
        lines += "  elements: ${elements ?: "(not reported)"}"
        lines += "  text nodes: ${texts ?: "(not reported)"}"

        lines += ""
        lines += "Box model (CSS pixels)"
        lines += boxLines(detail.box)

        val style = detail.style.orEmpty()
        lines += ""
        lines += "Computed style (a fixed set, not all of them)"
        if (style.isEmpty()) {
            lines += "  (the engine reported none)"
        } else {
            style.forEach { entry -> lines += "  ${entry.name.orEmpty()} = ${entry.value.orEmpty()}" }
        }

        val text = detail.text.orEmpty()
        lines += ""
        lines += if (detail.text == null) {
            "Text content -- the page did not report it."
        } else {
            "Text content, this node and its descendants" +
                if (detail.textTruncated == true) " (first ${text.length} characters)" else ""
        }
        if (text.isNotEmpty()) lines += "  $text"

        val html = detail.html.orEmpty()
        lines += ""
        lines += if (detail.html == null) {
            "HTML -- the page did not report it."
        } else {
            "HTML" + if (detail.htmlTruncated == true) " (first ${html.length} characters)" else ""
        }
        if (html.isNotEmpty()) lines += html
        return lines.joinToString("\n")
    }

    private fun boxLines(box: ElementBox?): List<String> {
        if (box == null) return listOf("  (the page did not report a box)")
        return listOf(
            "  rect on screen: x=${number(box.x)}, y=${number(box.y)}, " +
                "${number(box.width)} x ${number(box.height)}",
            "  content: ${number(box.contentWidth)} x ${number(box.contentHeight)}",
            "  padding: ${number(box.paddingTop)} ${number(box.paddingRight)} " +
                "${number(box.paddingBottom)} ${number(box.paddingLeft)}",
            "  border: ${number(box.borderTop)} ${number(box.borderRight)} " +
                "${number(box.borderBottom)} ${number(box.borderLeft)}",
            "  margin: ${number(box.marginTop)} ${number(box.marginRight)} " +
                "${number(box.marginBottom)} ${number(box.marginLeft)}"
        )
    }

    /** A number, or the honest word for one that was not reported. */
    private fun number(value: Double?): String {
        if (value == null) return "(not reported)"
        val rounded = kotlin.math.round(value * 100) / 100
        return if (rounded == kotlin.math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }
}
