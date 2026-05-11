package com.sakuravillager.manga_translator.translation.ocr

import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.TextDirection
import kotlin.math.abs

object OcrPostProcessor {
    fun refine(textlines: List<Quadrilateral>): List<Quadrilateral> {
        if (textlines.isEmpty()) return emptyList()

        val direction = resolveDirection(textlines)
        val indexedTextlines = textlines.withIndex().toList()
        val sorted = sortByReadingOrder(indexedTextlines, direction)
        return sorted.mapIndexed { readingOrderIndex, indexedQuad ->
            val (sourceIndex, quad) = indexedQuad
            quad.copy(
                text = normalizeText(quad.text),
                sourceIndex = quad.sourceIndex ?: sourceIndex,
                readingOrderIndex = readingOrderIndex,
            )
        }
    }

    private fun resolveDirection(textlines: List<Quadrilateral>): TextDirection {
        val explicit = textlines.map { it.direction }.filter { it != TextDirection.AUTO }
        if (explicit.isNotEmpty()) {
            val counts = explicit.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            val top = counts.first()
            val second = counts.getOrNull(1)
            if (second == null || top.value != second.value) {
                return top.key
            }
        }

        val horizontalScore = textlines.count { it.aspectRatio >= 1f }
        val verticalScore = textlines.size - horizontalScore
        if (verticalScore > horizontalScore) {
            return TextDirection.VERTICAL
        }

        var cjkCount = 0
        var hiraganaCount = 0
        var katakanaCount = 0
        var koreanCount = 0
        var arabicCount = 0
        var latinCount = 0

        for (quad in textlines) {
            for (char in quad.text) {
                when {
                    char in '\u3040'..'\u309f' -> hiraganaCount++
                    char in '\u30a0'..'\u30ff' -> katakanaCount++
                    char in '\uac00'..'\ud7af' || char in '\u1100'..'\u11ff' -> koreanCount++
                    char in '\u0600'..'\u06ff' || char in '\u0750'..'\u077f' || char in '\u08a0'..'\u08ff' -> arabicCount++
                    char.isLetter() && char.code < 128 -> latinCount++
                    char in '\u4e00'..'\u9fff' || char in '\u3400'..'\u4dbf' || char in '\uf900'..'\ufaff' -> cjkCount++
                }
            }
        }

        val rtlScore = cjkCount + hiraganaCount + katakanaCount + koreanCount + arabicCount
        val ltrScore = latinCount

        return when {
            rtlScore > ltrScore && rtlScore > 0 -> TextDirection.HORIZONTAL_RTL
            ltrScore > rtlScore && ltrScore > 0 -> TextDirection.HORIZONTAL
            arabicCount > 0 -> TextDirection.HORIZONTAL_RTL
            cjkCount + hiraganaCount + katakanaCount + koreanCount > 0 -> TextDirection.HORIZONTAL_RTL
            else -> TextDirection.HORIZONTAL
        }
    }

    private fun sortByReadingOrder(
        textlines: List<IndexedValue<Quadrilateral>>,
        direction: TextDirection,
    ): List<IndexedValue<Quadrilateral>> {
        if (textlines.size <= 1) return textlines

        return when (direction) {
            TextDirection.VERTICAL -> {
                groupByColumns(textlines)
            }
            TextDirection.HORIZONTAL_RTL -> {
                groupByRows(textlines, rtl = true)
            }
            else -> {
                groupByRows(textlines, rtl = false)
            }
        }
    }

    private fun groupByRows(textlines: List<IndexedValue<Quadrilateral>>, rtl: Boolean): List<IndexedValue<Quadrilateral>> {
        val primary = textlines.sortedWith(compareBy<IndexedValue<Quadrilateral>> { it.value.boundingBox.top }.thenBy { it.value.boundingBox.left })
        val averageHeight = primary.map { it.value.boundingBox.height() }.average().toFloat().coerceAtLeast(1f)
        val rowThreshold = maxOf(12f, averageHeight * 0.45f)

        val sorted = mutableListOf<IndexedValue<Quadrilateral>>()
        val row = mutableListOf<IndexedValue<Quadrilateral>>()
        var rowTop = Float.NaN
        var rowBottom = Float.NaN
        for (indexedQuad in primary) {
            val top = indexedQuad.value.boundingBox.top
            val bottom = indexedQuad.value.boundingBox.bottom
            if (row.isEmpty()) {
                row.add(indexedQuad)
                rowTop = top
                rowBottom = bottom
                continue
            }

            val overlapsCurrentRow = top <= rowBottom + rowThreshold && bottom >= rowTop - rowThreshold
            if (!overlapsCurrentRow) {
                sorted += sortRow(row, rtl)
                row.clear()
                row.add(indexedQuad)
                rowTop = top
                rowBottom = bottom
            } else {
                row.add(indexedQuad)
                rowTop = minOf(rowTop, top)
                rowBottom = maxOf(rowBottom, bottom)
            }
        }
        if (row.isNotEmpty()) {
            sorted += sortRow(row, rtl)
        }
        return sorted
    }

    private fun groupByColumns(textlines: List<IndexedValue<Quadrilateral>>): List<IndexedValue<Quadrilateral>> {
        val primary = textlines.sortedWith(compareByDescending<IndexedValue<Quadrilateral>> { it.value.boundingBox.left }.thenBy { it.value.boundingBox.top })
        val averageWidth = primary.map { it.value.boundingBox.width() }.average().toFloat().coerceAtLeast(1f)
        val columnThreshold = maxOf(12f, averageWidth * 0.45f)

        val sorted = mutableListOf<IndexedValue<Quadrilateral>>()
        val column = mutableListOf<IndexedValue<Quadrilateral>>()
        var columnLeft = Float.NaN
        var columnRight = Float.NaN
        for (indexedQuad in primary) {
            val left = indexedQuad.value.boundingBox.left
            val right = indexedQuad.value.boundingBox.right
            if (column.isEmpty()) {
                column.add(indexedQuad)
                columnLeft = left
                columnRight = right
                continue
            }

            val overlapsCurrentColumn = left <= columnRight + columnThreshold && right >= columnLeft - columnThreshold
            if (!overlapsCurrentColumn) {
                sorted += sortColumn(column)
                column.clear()
                column.add(indexedQuad)
                columnLeft = left
                columnRight = right
            } else {
                column.add(indexedQuad)
                columnLeft = minOf(columnLeft, left)
                columnRight = maxOf(columnRight, right)
            }
        }
        if (column.isNotEmpty()) {
            sorted += sortColumn(column)
        }
        return sorted
    }

    private fun sortRow(row: List<IndexedValue<Quadrilateral>>, rtl: Boolean): List<IndexedValue<Quadrilateral>> {
        return if (rtl) {
            row.sortedWith(
                compareByDescending<IndexedValue<Quadrilateral>> { it.value.center.x }
                    .thenBy { it.value.center.y }
                    .thenBy { it.index }
            )
        } else {
            row.sortedWith(
                compareBy<IndexedValue<Quadrilateral>> { it.value.center.x }
                    .thenBy { it.value.center.y }
                    .thenBy { it.index }
            )
        }
    }

    private fun sortColumn(column: List<IndexedValue<Quadrilateral>>): List<IndexedValue<Quadrilateral>> {
        return column.sortedWith(
            compareBy<IndexedValue<Quadrilateral>> { it.value.center.y }
                .thenByDescending { it.value.center.x }
                .thenBy { it.index }
        )
    }

    private fun normalizeText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed

        val squashedWhitespace = trimmed.replace(Regex("\\s+"), " ")
        return removeUnpairedSymbols(squashedWhitespace)
    }

    private fun removeUnpairedSymbols(text: String): String {
        val pairs = mapOf(
            '(' to ')',
            '（' to '）',
            '[' to ']',
            '［' to '］',
            '{' to '}',
            '｛' to '｝',
            '【' to '】',
            '「' to '」',
            '『' to '』',
            '〈' to '〉',
            '《' to '》',
            '<' to '>',
        )
        val closingToOpening = pairs.entries.associate { it.value to it.key }
        val stack = ArrayDeque<Pair<Int, Char>>()
        val toSkip = BooleanArray(text.length)

        for ((index, char) in text.withIndex()) {
            if (char in pairs.keys) {
                stack.addLast(index to char)
            } else if (char in closingToOpening.keys) {
                if (stack.isNotEmpty()) {
                    val last = stack.removeLast()
                    if (pairs[last.second] != char) {
                        toSkip[index] = true
                    }
                } else {
                    toSkip[index] = true
                }
            }
        }

        for ((index, _) in stack) {
            toSkip[index] = true
        }

        if (toSkip.none { it }) return text
        return buildString(text.length) {
            for ((index, char) in text.withIndex()) {
                if (!toSkip[index]) append(char)
            }
        }
    }
}