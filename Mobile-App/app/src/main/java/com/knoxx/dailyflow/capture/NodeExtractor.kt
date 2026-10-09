package com.knoxx.dailyflow.capture

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Flattens an accessibility node tree into the visible text of one screen.
 *
 * Ported from Wick essentially unchanged — both of the non-obvious details
 * here were real bugs found in production use, and both are easy to
 * reintroduce by "simplifying" this.
 */
object NodeExtractor {

    /** Guard against a pathological or cyclic tree. A real screen is well
     *  under this; Wick's day-long audit averaged 95 nodes per event. */
    private const val MAX_NODES = 2000

    data class Result(val text: String, val nodeCount: Int)

    fun extract(root: AccessibilityNodeInfo?): Result {
        if (root == null) return Result("", 0)

        val parts = mutableListOf<String>()
        var visited = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)

        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++

            node.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() && !isIconGlyphOnly(it) }
                ?.let { parts.add(it) }

            node.contentDescription?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() && !isIconGlyphOnly(it) }
                ?.let { parts.add(it) }

            // Pushed in reverse so the LIFO stack pops children back in their
            // natural forward order. Pushing 0..N-1 visits the LAST child
            // first, which scrambles reading order — confirmed root cause of a
            // real bug where a chat row's message preview came out ahead of
            // its own contact name, and the model then attributed the message
            // to the wrong person.
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        return Result(parts.joinToString(" "), visited)
    }

    /**
     * True when every character sits in a Unicode Private Use Area. Several
     * apps render icon fonts through a TextView, so `node.text` comes back as
     * glyph codepoints rather than words. Without this the model is fed
     * strings of meaningless symbols and dutifully tries to interpret them.
     */
    private fun isIconGlyphOnly(s: String): Boolean = s.codePoints().allMatch { cp ->
        (cp in 0xE000..0xF8FF) || (cp in 0xF0000..0xFFFFD) || (cp in 0x100000..0x10FFFD)
    }
}
