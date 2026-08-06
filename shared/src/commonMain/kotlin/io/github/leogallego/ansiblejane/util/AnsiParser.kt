package io.github.leogallego.ansiblejane.util

/**
 * Named ANSI SGR colors used by job stdout (Ansible/AWX).
 * UI maps these to Material 3 tokens.
 */
enum class AnsiNamedColor {
    BLACK,
    RED,
    GREEN,
    YELLOW,
    BLUE,
    MAGENTA,
    CYAN,
    WHITE,
    BRIGHT_BLACK,
    BRIGHT_RED,
    BRIGHT_GREEN,
    BRIGHT_YELLOW,
    BRIGHT_BLUE,
    BRIGHT_MAGENTA,
    BRIGHT_CYAN,
    BRIGHT_WHITE,
}

data class AnsiStyle(
    val foreground: AnsiNamedColor? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
) {
    companion object {
        val Default = AnsiStyle()
    }
}

data class AnsiSpan(
    val text: String,
    val style: AnsiStyle = AnsiStyle.Default,
)

/**
 * Pure ANSI CSI/SGR parser for job stdout. No Compose dependency.
 *
 * Handles colors, bold, and dim. Incomplete escape sequences at the end of
 * [input] are treated as plain text (no dropped characters, no janky wait).
 */
object AnsiParser {
    private const val ESC = '\u001B'
    private const val MAX_CSI_LENGTH = 64

    fun parse(input: String): List<AnsiSpan> {
        if (input.isEmpty()) return emptyList()
        if (ESC !in input) return listOf(AnsiSpan(input))

        val spans = ArrayList<AnsiSpan>()
        val buffer = StringBuilder()
        var style = AnsiStyle.Default
        var i = 0

        fun flush() {
            if (buffer.isEmpty()) return
            spans += AnsiSpan(buffer.toString(), style)
            buffer.clear()
        }

        while (i < input.length) {
            val c = input[i]
            if (c != ESC) {
                buffer.append(c)
                i++
                continue
            }

            // Incomplete ESC at end — keep as literal.
            if (i + 1 >= input.length) {
                buffer.append(c)
                i++
                continue
            }

            val next = input[i + 1]
            if (next != '[') {
                // Non-CSI escape (e.g. OSC) — skip ESC and continue; keep following char.
                buffer.append(c)
                i++
                continue
            }

            // Parse CSI: ESC [ params final
            val paramsStart = i + 2
            var j = paramsStart
            var foundFinal = false
            while (j < input.length && j - paramsStart < MAX_CSI_LENGTH) {
                val ch = input[j]
                if (ch in '@'..'~') {
                    foundFinal = true
                    break
                }
                j++
            }

            if (!foundFinal) {
                if (j >= input.length) {
                    // Incomplete CSI at EOF — keep remainder as plain text.
                    buffer.append(input, i, input.length)
                    break
                }
                // Overlong / malformed CSI mid-stream — keep ESC as literal and resume
                // so the rest of the log can still be colored.
                buffer.append(ESC)
                i++
                continue
            }

            val finalByte = input[j]
            val params = input.substring(paramsStart, j)
            flush()
            if (finalByte == 'm') {
                style = applySgr(style, params)
            }
            // Other CSI sequences (cursor moves, etc.) are stripped.
            i = j + 1
        }

        flush()
        return mergeAdjacent(spans)
    }

    /** Strip ANSI escapes, returning plain text. */
    fun strip(input: String): String =
        parse(input).joinToString(separator = "") { it.text }

    private fun applySgr(current: AnsiStyle, params: String): AnsiStyle {
        if (params.isEmpty()) return AnsiStyle.Default

        var style = current
        val codes = params.split(';').mapNotNull { it.toIntOrNull() }
        if (codes.isEmpty()) return AnsiStyle.Default

        var idx = 0
        while (idx < codes.size) {
            when (val code = codes[idx]) {
                0 -> style = AnsiStyle.Default
                1 -> style = style.copy(bold = true, dim = false)
                2 -> style = style.copy(dim = true, bold = false)
                22 -> style = style.copy(bold = false, dim = false)
                39 -> style = style.copy(foreground = null)
                in 30..37 -> style = style.copy(foreground = standardColor(code - 30, bright = false))
                in 90..97 -> style = style.copy(foreground = standardColor(code - 90, bright = true))
                38 -> {
                    // 38;5;n or 38;2;r;g;b — approximate to nearest named color or ignore.
                    val (color, consumed) = parseExtendedColor(codes, idx + 1)
                    if (color != null) style = style.copy(foreground = color)
                    idx += consumed
                }
                // Background codes intentionally ignored for monospace job logs.
                in 40..47, in 100..107, 48, 49 -> {
                    if (code == 48) {
                        val (_, consumed) = parseExtendedColor(codes, idx + 1)
                        idx += consumed
                    }
                }
            }
            idx++
        }
        return style
    }

    private fun parseExtendedColor(codes: List<Int>, start: Int): Pair<AnsiNamedColor?, Int> {
        if (start >= codes.size) return null to 0
        return when (codes[start]) {
            5 -> {
                if (start + 1 >= codes.size) return null to 1
                val n = codes[start + 1]
                approximate256(n) to 2
            }
            2 -> {
                // Skip r;g;b — no named mapping; consume 4 values (2 + r + g + b) relative to start-1... 
                // codes[start]=2, then r,g,b → consume 4
                if (start + 3 >= codes.size) return null to (codes.size - start)
                null to 4
            }
            else -> null to 0
        }
    }

    private fun approximate256(n: Int): AnsiNamedColor? = when (n) {
        in 0..7 -> standardColor(n, bright = false)
        in 8..15 -> standardColor(n - 8, bright = true)
        else -> null
    }

    private fun standardColor(index: Int, bright: Boolean): AnsiNamedColor =
        when (index) {
            0 -> if (bright) AnsiNamedColor.BRIGHT_BLACK else AnsiNamedColor.BLACK
            1 -> if (bright) AnsiNamedColor.BRIGHT_RED else AnsiNamedColor.RED
            2 -> if (bright) AnsiNamedColor.BRIGHT_GREEN else AnsiNamedColor.GREEN
            3 -> if (bright) AnsiNamedColor.BRIGHT_YELLOW else AnsiNamedColor.YELLOW
            4 -> if (bright) AnsiNamedColor.BRIGHT_BLUE else AnsiNamedColor.BLUE
            5 -> if (bright) AnsiNamedColor.BRIGHT_MAGENTA else AnsiNamedColor.MAGENTA
            6 -> if (bright) AnsiNamedColor.BRIGHT_CYAN else AnsiNamedColor.CYAN
            else -> if (bright) AnsiNamedColor.BRIGHT_WHITE else AnsiNamedColor.WHITE
        }

    private fun mergeAdjacent(spans: List<AnsiSpan>): List<AnsiSpan> {
        if (spans.size <= 1) return spans
        val merged = ArrayList<AnsiSpan>(spans.size)
        var current = spans.first()
        for (i in 1 until spans.size) {
            val next = spans[i]
            if (next.style == current.style) {
                current = AnsiSpan(current.text + next.text, current.style)
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }
}
