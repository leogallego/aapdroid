package io.github.leogallego.ansiblejane.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnsiParserTest {

    @Test
    fun plain_text_passthrough() {
        val spans = AnsiParser.parse("PLAY [all] ***")
        assertEquals(1, spans.size)
        assertEquals("PLAY [all] ***", spans.single().text)
        assertEquals(AnsiStyle.Default, spans.single().style)
    }

    @Test
    fun strips_and_colors_basic_sgr() {
        val input = "\u001B[31mFAILED\u001B[0m ok"
        val spans = AnsiParser.parse(input)
        assertEquals(2, spans.size)
        assertEquals("FAILED", spans[0].text)
        assertEquals(AnsiNamedColor.RED, spans[0].style.foreground)
        assertEquals(" ok", spans[1].text)
        assertEquals(AnsiStyle.Default, spans[1].style)
        assertEquals("FAILED ok", AnsiParser.strip(input))
    }

    @Test
    fun bold_and_bright_green() {
        val input = "\u001B[1;92mchanged\u001B[0m"
        val spans = AnsiParser.parse(input)
        assertEquals(1, spans.size)
        assertEquals("changed", spans[0].text)
        assertTrue(spans[0].style.bold)
        assertEquals(AnsiNamedColor.BRIGHT_GREEN, spans[0].style.foreground)
    }

    @Test
    fun dim_style() {
        val input = "\u001B[2mskipping\u001B[22m done"
        val spans = AnsiParser.parse(input)
        assertEquals(2, spans.size)
        assertTrue(spans[0].style.dim)
        assertEquals("skipping", spans[0].text)
        assertEquals(" done", spans[1].text)
        assertEquals(AnsiStyle.Default, spans[1].style)
    }

    @Test
    fun incomplete_escape_kept_as_plain_text() {
        val input = "hello \u001B[31"
        val spans = AnsiParser.parse(input)
        assertEquals(1, spans.size)
        assertEquals(input, spans.single().text)
    }

    @Test
    fun incomplete_esc_only_kept() {
        val input = "tail\u001B"
        val spans = AnsiParser.parse(input)
        assertEquals("tail\u001B", spans.single().text)
    }

    @Test
    fun merges_adjacent_same_style() {
        val input = "\u001B[32ma\u001B[32mb\u001B[0m"
        val spans = AnsiParser.parse(input)
        assertEquals(1, spans.size)
        assertEquals("ab", spans.single().text)
        assertEquals(AnsiNamedColor.GREEN, spans.single().style.foreground)
    }

    @Test
    fun strips_non_sgr_csi() {
        val input = "a\u001B[2Kb"
        assertEquals("ab", AnsiParser.strip(input))
    }

    @Test
    fun ansible_like_mixed_line() {
        val input = "\u001B[0;32mok\u001B[0m: [localhost] => \u001B[0;33m{\u001B[0m\"msg\": \"hi\"\u001B[0;33m}\u001B[0m"
        val text = AnsiParser.strip(input)
        assertEquals("ok: [localhost] => {\"msg\": \"hi\"}", text)
        val spans = AnsiParser.parse(input)
        assertTrue(spans.any { it.style.foreground == AnsiNamedColor.GREEN })
        assertTrue(spans.any { it.style.foreground == AnsiNamedColor.YELLOW })
    }
}
