package io.github.leogallego.ansiblejane.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import io.github.leogallego.ansiblejane.util.AnsiNamedColor
import io.github.leogallego.ansiblejane.util.AnsiParser
import io.github.leogallego.ansiblejane.util.AnsiSpan
import io.github.leogallego.ansiblejane.util.AnsiStyle

/**
 * Monospace job stdout with ANSI SGR colors mapped to Material 3 tokens.
 * Parsing is memoized on [text] to avoid jank on long logs.
 */
@Composable
fun AnsiText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val onSurface = colorScheme.onSurface
    val annotated = remember(text, colorScheme) {
        ansiToAnnotatedString(
            spans = AnsiParser.parse(text),
            defaultColor = onSurface,
            colorFor = { named -> named.toMaterialColor(colorScheme) },
        )
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            color = onSurface,
        ),
        modifier = modifier.testTag("text_job_stdout"),
    )
}

internal fun ansiToAnnotatedString(
    spans: List<AnsiSpan>,
    defaultColor: Color,
    colorFor: (AnsiNamedColor) -> Color,
): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        if (span.text.isEmpty()) continue
        withStyle(span.style.toSpanStyle(defaultColor, colorFor)) {
            append(span.text)
        }
    }
}

private fun AnsiStyle.toSpanStyle(
    defaultColor: Color,
    colorFor: (AnsiNamedColor) -> Color,
): SpanStyle {
    val base = foreground?.let(colorFor) ?: defaultColor
    val color = when {
        dim -> base.copy(alpha = 0.55f)
        else -> base
    }
    return SpanStyle(
        color = color,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    )
}

private fun AnsiNamedColor.toMaterialColor(
    colors: androidx.compose.material3.ColorScheme,
): Color = when (this) {
    AnsiNamedColor.BLACK, AnsiNamedColor.BRIGHT_BLACK -> colors.onSurface.copy(alpha = 0.7f)
    AnsiNamedColor.RED, AnsiNamedColor.BRIGHT_RED -> colors.error
    AnsiNamedColor.GREEN, AnsiNamedColor.BRIGHT_GREEN -> colors.tertiary
    AnsiNamedColor.YELLOW, AnsiNamedColor.BRIGHT_YELLOW -> colors.secondary
    AnsiNamedColor.BLUE, AnsiNamedColor.BRIGHT_BLUE -> colors.primary
    AnsiNamedColor.MAGENTA, AnsiNamedColor.BRIGHT_MAGENTA -> colors.primary.copy(alpha = 0.85f)
    AnsiNamedColor.CYAN, AnsiNamedColor.BRIGHT_CYAN -> colors.secondary.copy(alpha = 0.9f)
    AnsiNamedColor.WHITE, AnsiNamedColor.BRIGHT_WHITE -> colors.onSurface
}
