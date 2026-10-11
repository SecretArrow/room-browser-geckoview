package com.roombrowser.devtools

import kotlinx.serialization.Serializable

/**
 * The Elements panel's DOM reads, and the models they decode into.
 *
 * NODE IDS LIVE IN A PAGE-SIDE REGISTRY, NOT IN THE MARKUP. Marking real
 * elements with `data-rb-node` attributes would change the page's own DOM --
 * visible to the page, to its CSS selectors and in the very `outerHTML` this
 * panel prints -- so instead a window global maps an integer to the node, and
 * every read names the node by that integer.
 *
 * THE GENERATION IS WHAT MAKES A STALE ID SAFE. The registry is thrown away and
 * rebuilt on every tree read, so an id from before a navigation or a refresh
 * would silently address a different node. Each read carries the generation it
 * was handed and answers `stale` rather than resolving an id it does not own.
 *
 * BOUNDED IN THREE PLACES, because a phone is not a desktop: a node budget the
 * registry will not grow past, a child page size, and character caps on the text
 * and HTML a detail carries. Each cap is reported to the panel as the number it
 * actually was, so a truncated answer can never read as a complete one.
 */
internal object DeveloperToolsElementsScripts {

    /** Registered nodes one tree read may hold. Past this the tree read says so and stops growing. */
    internal const val NODE_BUDGET = 2_000

    /** Child elements one expansion fetches. The remainder is one "show more" away. */
    internal const val CHILD_PAGE = 50

    /** Characters of a node's text content a detail carries. */
    internal const val TEXT_CAP = 400

    /** Characters of `outerHTML` a detail carries. */
    internal const val HTML_CAP = 2_000

    /**
     * Helpers shared by all three reads.
     *
     * `reg` and `budget` are parameters rather than captured names: each read is
     * its own evaluation in the page, so the only thing they can share is text.
     */
    private const val HELPERS = """
        function __rbAttr(node, name) {
          try { return node.getAttribute ? (node.getAttribute(name) || '') : ''; } catch (e) { return ''; }
        }
        function __rbTextNodes(node) {
          var n, k = 0, i;
          try { n = node.childNodes; } catch (e) { return null; }
          for (i = 0; i < n.length; i++) { if (n[i].nodeType === 3) k++; }
          return k;
        }
        function __rbRegister(reg, budget, node) {
          if (!node || node.nodeType !== 1) return null;
          if (reg.count >= budget) { reg.exhausted = true; return null; }
          var id = reg.next;
          reg.next = id + 1;
          reg.count = reg.count + 1;
          reg.nodes[id] = node;
          return {
            id: id,
            tag: (node.tagName || '').toLowerCase(),
            idAttr: __rbAttr(node, 'id'),
            classes: __rbAttr(node, 'class'),
            children: node.children ? node.children.length : 0,
            childTextNodes: __rbTextNodes(node)
          };
        }
    """

    /**
     * Reads the document root and starts a new registry.
     *
     * The root is always `<html>`: walking up from anything else would mean
     * choosing a node for the user, and the document element is the one node
     * every page has.
     */
    fun elementsTreeJs(): String = """
        (function () {
          $HELPERS
          var gen = 1;
          try {
            if (window.__rbDom && typeof window.__rbDom.gen === 'number') gen = window.__rbDom.gen + 1;
          } catch (e) {}
          var reg = { gen: gen, next: 1, count: 0, exhausted: false, nodes: {} };
          window.__rbDom = reg;
          return JSON.stringify({
            gen: reg.gen,
            budget: $NODE_BUDGET,
            exhausted: reg.exhausted,
            node: __rbRegister(reg, $NODE_BUDGET, document.documentElement)
          });
        })()
    """.trimIndent()

    /** One page of [parentId]'s element children, starting at [from]. */
    fun elementsChildrenJs(gen: Int, parentId: Int, from: Int): String = """
        (function () {
          $HELPERS
          var st = {
            gen: $gen, stale: true, parentId: $parentId, from: $from,
            total: 0, exhausted: false, nodes: []
          };
          var reg = window.__rbDom;
          if (!reg || reg.gen !== $gen) return JSON.stringify(st);
          var parent = reg.nodes[$parentId];
          if (!parent) return JSON.stringify(st);
          var kids = parent.children;
          if (!kids) { st.stale = false; return JSON.stringify(st); }
          st.stale = false;
          st.total = kids.length;
          var end = Math.min(kids.length, $from + $CHILD_PAGE);
          for (var i = $from; i < end; i++) {
            var described = __rbRegister(reg, $NODE_BUDGET, kids[i]);
            if (described) st.nodes.push(described);
          }
          st.exhausted = reg.exhausted === true;
          return JSON.stringify(st);
        })()
    """.trimIndent()

    /**
     * One node's attributes, box, computed style and text.
     *
     * `detached` is reported rather than inferred from a zero-sized rect: a node
     * removed from the document measures 0x0 for a real reason, and a panel that
     * did not say which of the two it was looking at would send a reader hunting
     * for a layout bug that is not there.
     */
    fun elementsNodeJs(gen: Int, nodeId: Int): String = """
        (function () {
          $HELPERS
          var st = {
            gen: $gen, stale: true, detached: null, tag: '', idAttr: '', classes: '',
            attributes: [], childElements: null, childTextNodes: null,
            text: '', textTruncated: false, html: '', htmlTruncated: false,
            style: [], box: null
          };
          var reg = window.__rbDom;
          if (!reg || reg.gen !== $gen) return JSON.stringify(st);
          var node = reg.nodes[$nodeId];
          if (!node) return JSON.stringify(st);
          st.stale = false;
          st.tag = (node.tagName || '').toLowerCase();
          st.idAttr = __rbAttr(node, 'id');
          st.classes = __rbAttr(node, 'class');
          var root = document.documentElement;
          st.detached = !(root && root.contains && root.contains(node));
          st.childElements = node.children ? node.children.length : 0;
          st.childTextNodes = __rbTextNodes(node);

          try {
            var a = node.attributes, i;
            for (i = 0; a && i < a.length; i++) {
              st.attributes.push({ name: String(a[i].name), value: String(a[i].value) });
            }
          } catch (e) {}

          // A FIXED SET, NAMED AS ONE. `getComputedStyle` answers for hundreds of
          // properties; this is the handful that explains a layout on a phone,
          // and the panel says "a fixed set" rather than implying completeness.
          var names = [
            'display', 'position', 'width', 'height', 'color', 'background-color',
            'font-size', 'font-weight', 'line-height', 'overflow', 'z-index',
            'opacity', 'visibility', 'text-align'
          ];
          function __rbPx(style, name) {
            var raw = '';
            try { raw = style.getPropertyValue(name); } catch (e) { return null; }
            var n = parseFloat(raw);
            return isFinite(n) ? Math.round(n * 100) / 100 : null;
          }
          var style = null;
          try { style = window.getComputedStyle(node); } catch (e) {}
          if (style) {
            for (var s = 0; s < names.length; s++) {
              var value = '';
              try { value = style.getPropertyValue(names[s]) || ''; } catch (e) {}
              if (value) st.style.push({ name: names[s], value: value });
            }
            // `width`/`height` here are the CONTENT box, which is what the CSS
            // says; the rect below is the border box on screen. Both are printed,
            // because conflating them is the usual way a box model misleads.
            var rect = null;
            try { rect = node.getBoundingClientRect(); } catch (e) {}
            st.box = {
              contentWidth: __rbPx(style, 'width'), contentHeight: __rbPx(style, 'height'),
              paddingTop: __rbPx(style, 'padding-top'), paddingRight: __rbPx(style, 'padding-right'),
              paddingBottom: __rbPx(style, 'padding-bottom'), paddingLeft: __rbPx(style, 'padding-left'),
              borderTop: __rbPx(style, 'border-top-width'), borderRight: __rbPx(style, 'border-right-width'),
              borderBottom: __rbPx(style, 'border-bottom-width'), borderLeft: __rbPx(style, 'border-left-width'),
              marginTop: __rbPx(style, 'margin-top'), marginRight: __rbPx(style, 'margin-right'),
              marginBottom: __rbPx(style, 'margin-bottom'), marginLeft: __rbPx(style, 'margin-left'),
              x: rect ? Math.round(rect.left * 100) / 100 : null,
              y: rect ? Math.round(rect.top * 100) / 100 : null,
              width: rect ? Math.round(rect.width * 100) / 100 : null,
              height: rect ? Math.round(rect.height * 100) / 100 : null
            };
          }

          var text = '';
          try { text = (node.textContent || '').replace(/\s+/g, ' ').trim(); } catch (e) {}
          st.textTruncated = text.length > $TEXT_CAP;
          st.text = text.slice(0, $TEXT_CAP);
          var html = '';
          try { html = node.outerHTML || ''; } catch (e) {}
          st.htmlTruncated = html.length > $HTML_CAP;
          st.html = html.slice(0, $HTML_CAP);
          return JSON.stringify(st);
        })()
    """.trimIndent()
}

/** The root read: the registry's generation, and `<html>`. */
@Serializable
data class ElementsTree(
    val gen: Int? = null,
    val budget: Int? = null,
    val exhausted: Boolean? = null,
    val node: ElementNode? = null
)

/** One element in the tree, as a row needs it. */
@Serializable
data class ElementNode(
    val id: Int? = null,
    val tag: String? = null,
    val idAttr: String? = null,
    val classes: String? = null,
    val children: Int? = null,
    val childTextNodes: Int? = null
)

/** One page of a node's children. [stale] means the registry was rebuilt and this id is not ours any more. */
@Serializable
data class ElementChildren(
    val gen: Int? = null,
    val stale: Boolean? = null,
    val parentId: Int? = null,
    val from: Int? = null,
    val total: Int? = null,
    val exhausted: Boolean? = null,
    val nodes: List<ElementNode>? = null
)

@Serializable
data class ElementAttribute(val name: String? = null, val value: String? = null)

@Serializable
data class ElementStyleValue(val name: String? = null, val value: String? = null)

/**
 * The box model, in CSS pixels.
 *
 * [x], [y], [width] and [height] come from `getBoundingClientRect` and are the
 * border box on screen; the content size and the edges come from the computed
 * style. They are deliberately separate fields rather than one sum.
 */
@Serializable
data class ElementBox(
    val contentWidth: Double? = null,
    val contentHeight: Double? = null,
    val paddingTop: Double? = null,
    val paddingRight: Double? = null,
    val paddingBottom: Double? = null,
    val paddingLeft: Double? = null,
    val borderTop: Double? = null,
    val borderRight: Double? = null,
    val borderBottom: Double? = null,
    val borderLeft: Double? = null,
    val marginTop: Double? = null,
    val marginRight: Double? = null,
    val marginBottom: Double? = null,
    val marginLeft: Double? = null,
    val x: Double? = null,
    val y: Double? = null,
    val width: Double? = null,
    val height: Double? = null
)

/** One selected node, as the detail section needs it. */
@Serializable
data class ElementDetail(
    val gen: Int? = null,
    val stale: Boolean? = null,
    val detached: Boolean? = null,
    val tag: String? = null,
    val idAttr: String? = null,
    val classes: String? = null,
    val attributes: List<ElementAttribute>? = null,
    val childElements: Int? = null,
    val childTextNodes: Int? = null,
    val text: String? = null,
    val textTruncated: Boolean? = null,
    val html: String? = null,
    val htmlTruncated: Boolean? = null,
    val style: List<ElementStyleValue>? = null,
    val box: ElementBox? = null
)
