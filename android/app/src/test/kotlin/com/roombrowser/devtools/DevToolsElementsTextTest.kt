package com.roombrowser.devtools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * WHAT THESE PIN. The Elements panel is built out of two pure pieces -- the walk
 * that decides which rows are on screen, and the formatter -- and both can state
 * something FALSE without any of it showing on a device.
 *
 * The walk is the one that matters: it is what bounds the panel's cost, and a
 * version that walked the document instead of the reader's expansions would look
 * identical on a small page while hanging on a large one. So the tests below
 * assert that an unopened child is never rendered, and that a node fetched but
 * not expanded is not rendered either.
 *
 * The formatter tests are all the same shape: a limit must name itself. A capped
 * text, a detached node measured at 0x0, a style the engine did not report --
 * each of those has a truthful sentence and a plausible lie, and each lie reads
 * as a finding about the page.
 */
class DevToolsElementsTextTest {

    private fun node(
        id: Int,
        tag: String,
        idAttr: String = "",
        classes: String = "",
        children: Int = 0,
        textNodes: Int = 0
    ) = ElementNode(
        id = id,
        tag = tag,
        idAttr = idAttr,
        classes = classes,
        children = children,
        childTextNodes = textNodes
    )

    // html > (head, body > (div#main.card, p))
    private val html = node(1, "html", children = 2, textNodes = 1)
    private val head = node(2, "head", children = 1)
    private val body = node(3, "body", children = 2)
    private val div = node(4, "div", idAttr = "main", classes = "card wide", children = 1)
    private val span = node(5, "span")
    private val paragraph = node(6, "p", textNodes = 1)

    private val loaded = mapOf(
        1 to listOf(head, body),
        3 to listOf(div, paragraph),
        4 to listOf(span)
    )

    private fun rowIds(expanded: Set<Int>) =
        DeveloperToolsElementsText.visibleRows(html, loaded, expanded).map { it.node.id }

    @Test
    fun only_the_expanded_nodes_children_are_rendered() {
        // The root, and nothing under it: this is the state the panel opens in,
        // and it must cost one row no matter how large the document is.
        assertThat(rowIds(emptySet())).containsExactly(1)
    }

    @Test
    fun opening_one_node_renders_only_that_nodes_children() {
        assertThat(rowIds(setOf(1))).containsExactly(1, 2, 3).inOrder()
        assertThat(rowIds(setOf(1, 3))).containsExactly(1, 2, 3, 4, 6).inOrder()
    }

    @Test
    fun a_node_whose_children_were_fetched_but_which_is_not_expanded_stays_hidden() {
        // `loaded` holds body's children, and body is fetched by opening html --
        // but an unopened body must still be a dead end, or "expand" would mean
        // nothing and the whole document would arrive one tap in.
        assertThat(rowIds(setOf(1))).doesNotContain(4)
        assertThat(rowIds(setOf(1, 2))).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun depth_is_the_distance_from_the_root_and_is_reported_per_row() {
        val rows = DeveloperToolsElementsText.visibleRows(html, loaded, setOf(1, 3, 4))
        assertThat(rows.map { it.node.tag to it.depth })
            .containsExactly(
                "html" to 0,
                "head" to 1,
                "body" to 1,
                "div" to 2,
                "span" to 3,
                "p" to 2
            ).inOrder()
    }

    @Test
    fun a_node_with_no_id_is_a_leaf_in_the_walk_rather_than_a_crash() {
        val rows = DeveloperToolsElementsText.visibleRows(ElementNode(tag = "html"), loaded, setOf(1))
        assertThat(rows.map { it.node.tag }).containsExactly("html")
    }

    @Test
    fun a_null_root_is_an_empty_tree_rather_than_a_throw() {
        assertThat(DeveloperToolsElementsText.visibleRows(null, loaded, setOf(1))).isEmpty()
    }

    @Test
    fun a_selector_names_the_node_the_way_a_reader_would_say_it() {
        assertThat(DeveloperToolsElementsText.selector(div)).isEqualTo("div#main.card.wide")
        assertThat(DeveloperToolsElementsText.selector(span)).isEqualTo("span")
        assertThat(DeveloperToolsElementsText.selector(html)).isEqualTo("html")
    }

    @Test
    fun a_row_says_how_many_children_and_text_nodes_it_holds_and_nothing_when_it_holds_neither() {
        assertThat(DeveloperToolsElementsText.rowText(body)).contains("2 elements")
        assertThat(DeveloperToolsElementsText.rowText(html)).contains("1 text node")
        assertThat(DeveloperToolsElementsText.rowText(span)).isEqualTo("span")
    }

    @Test
    fun the_tree_report_keeps_the_indentation_it_shows_on_screen() {
        val report = DeveloperToolsElementsText.treeReport(
            DeveloperToolsElementsText.visibleRows(html, loaded, setOf(1, 3)),
            "TestEngine"
        )
        assertThat(report).startsWith("Elements -- engine: TestEngine")
        assertThat(report).contains("\nhtml -- 2 elements, 1 text node")
        assertThat(report).contains("\n  head -- 1 element")
        assertThat(report).contains("\n    div#main.card.wide -- 1 element")
    }

    @Test
    fun an_empty_tree_says_so_instead_of_reporting_a_header_with_nothing_under_it() {
        val report = DeveloperToolsElementsText.treeReport(emptyList(), "TestEngine")
        assertThat(report).contains("(no tree was read)")
    }

    @Test
    fun the_tree_summary_says_the_budget_was_hit_rather_than_only_showing_a_short_tree() {
        val state = ElementsTreeState()
        state.adopt(ElementsTree(gen = 1, budget = 2_000, exhausted = false, node = html))
        state.addChildren(1, ElementChildren(total = 2, nodes = listOf(head, body)))
        assertThat(DeveloperToolsElementsText.treeSummary(state, 3))
            .isEqualTo("3 rows on screen, 2 child nodes read across 0 expanded nodes.")

        state.budgetExhausted = true
        assertThat(DeveloperToolsElementsText.treeSummary(state, 3)).contains("more nodes than this panel will hold")
    }

    @Test
    fun a_stale_selection_is_reported_instead_of_showing_another_nodes_attributes() {
        val detail = ElementDetail(gen = 1, stale = true, tag = "div")
        val text = DeveloperToolsElementsText.nodeDetailText(detail, "TestEngine")
        assertThat(text).contains("no longer valid")
        assertThat(text).doesNotContain("Attributes")
    }

    @Test
    fun an_unselected_panel_says_no_node_is_selected() {
        assertThat(DeveloperToolsElementsText.nodeDetailText(null, "TestEngine"))
            .contains("No node is selected.")
    }

    @Test
    fun a_detached_node_is_named_as_detached_rather_than_shown_as_a_zero_sized_box() {
        val detail = ElementDetail(
            gen = 1,
            stale = false,
            detached = true,
            tag = "div",
            box = ElementBox(x = 0.0, y = 0.0, width = 0.0, height = 0.0)
        )
        val text = DeveloperToolsElementsText.nodeDetailText(detail, "TestEngine")
        assertThat(text).contains("no longer in the document")
        assertThat(text).contains("rect on screen: x=0, y=0, 0 x 0")
    }

    @Test
    fun a_capped_text_and_a_capped_html_each_name_the_limit_they_hit() {
        val detail = ElementDetail(
            gen = 1,
            stale = false,
            tag = "p",
            text = "x".repeat(400),
            textTruncated = true,
            html = "<p>" + "y".repeat(1_997),
            htmlTruncated = true
        )
        val text = DeveloperToolsElementsText.nodeDetailText(detail, "TestEngine")
        assertThat(text).contains("(first 400 characters)")
        assertThat(text).contains("(first 2000 characters)")
    }

    @Test
    fun an_uncapped_text_and_html_carry_no_limit_line() {
        val detail = ElementDetail(
            gen = 1,
            stale = false,
            tag = "p",
            text = "hello",
            textTruncated = false,
            html = "<p>hello</p>",
            htmlTruncated = false
        )
        val text = DeveloperToolsElementsText.nodeDetailText(detail, "TestEngine")
        assertThat(text).doesNotContain("first ")
        assertThat(text).contains("  hello")
        assertThat(text).contains("<p>hello</p>")
    }

    @Test
    fun every_box_field_the_page_did_not_report_says_so_rather_than_printing_zero() {
        val detail = ElementDetail(gen = 1, stale = false, tag = "div", box = ElementBox())
        val text = DeveloperToolsElementsText.nodeDetailText(detail, "TestEngine")
        assertThat(text).contains(
            "rect on screen: x=(not reported), y=(not reported), (not reported) x (not reported)"
        )
    }

    @Test
    fun a_whole_number_in_the_box_is_printed_without_a_trailing_decimal() {
        val detail = ElementDetail(
            gen = 1,
            stale = false,
            tag = "div",
            box = ElementBox(contentWidth = 320.0, contentHeight = 12.5, x = 8.0, y = 4.0, width = 304.0, height = 20.0)
        )
        val text = DeveloperToolsElementsText.nodeDetailText(detail, "TestEngine")
        assertThat(text).contains("rect on screen: x=8, y=4, 304 x 20")
        assertThat(text).contains("content: 320 x 12.5")
    }

    @Test
    fun a_style_the_engine_reported_none_of_says_that_rather_than_nothing() {
        val detail = ElementDetail(gen = 1, stale = false, tag = "div", style = emptyList())
        val text = DeveloperToolsElementsText.nodeDetailText(detail, "TestEngine")
        assertThat(text).contains("Computed style (a fixed set, not all of them)")
        assertThat(text).contains("(the engine reported none)")
    }

    @Test
    fun the_detail_states_the_child_counts_it_was_given() {
        val detail = ElementDetail(gen = 1, stale = false, tag = "body", childElements = 2, childTextNodes = 3)
        val text = DeveloperToolsElementsText.nodeDetailText(detail, "TestEngine")
        assertThat(text).contains("elements: 2")
        assertThat(text).contains("text nodes: 3")
    }

    @Test
    fun an_adopted_tree_clears_the_previous_generations_nodes_and_selection() {
        val state = ElementsTreeState()
        state.adopt(ElementsTree(gen = 1, node = html))
        state.expanded += 3
        state.addChildren(3, ElementChildren(parentId = 3, total = 2, nodes = listOf(div, paragraph)))
        state.selected = 4
        state.detail = ElementDetail(gen = 1, tag = "div")

        state.adopt(ElementsTree(gen = 2, node = html))

        assertThat(state.generation).isEqualTo(2)
        assertThat(state.children).isEmpty()
        assertThat(state.totals).isEmpty()
        assertThat(state.expanded).isEmpty()
        assertThat(state.selected).isNull()
        assertThat(state.detail).isNull()
        assertThat(state.stale).isFalse()
    }

    @Test
    fun a_second_page_appends_so_the_next_fetch_asks_for_what_is_not_held_yet() {
        val state = ElementsTreeState()
        state.adopt(ElementsTree(gen = 1, node = html))
        state.addChildren(3, ElementChildren(total = 3, nodes = listOf(div, paragraph)))
        assertThat(state.nextFrom(3)).isEqualTo(2)
        state.addChildren(3, ElementChildren(total = 3, nodes = listOf(span)))
        assertThat(state.children[3]?.map { it.id }).containsExactly(4, 6, 5).inOrder()
        assertThat(state.totals[3]).isEqualTo(3)
    }

    @Test
    fun a_child_without_an_id_is_not_held_because_it_could_never_be_addressed_again() {
        val state = ElementsTreeState()
        state.adopt(ElementsTree(gen = 1, node = html))
        state.addChildren(3, ElementChildren(total = 1, nodes = listOf(ElementNode(tag = "div"))))
        assertThat(state.children[3]).isEmpty()
    }
}
