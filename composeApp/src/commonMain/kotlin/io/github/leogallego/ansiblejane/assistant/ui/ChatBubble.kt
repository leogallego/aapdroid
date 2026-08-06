package io.github.leogallego.ansiblejane.assistant.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.leogallego.ansiblejane.assistant.engine.ChatMessage
import io.github.leogallego.ansiblejane.assistant.engine.ResponseSource
import io.github.leogallego.ansiblejane.assistant.engine.TokenUsage
import io.github.leogallego.ansiblejane.assistant.engine.ToolUsage
import com.mikepenz.markdown.compose.LocalBulletListHandler
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.BulletHandler
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import org.jetbrains.compose.resources.stringResource
import aapremotecontrol.composeapp.generated.resources.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserBubble(
    message: ChatMessage,
    onCopy: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Spacer(Modifier.weight(1f))
        Box {
            Column(
                modifier = Modifier
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { menuExpanded = true },
                    )
                    .background(
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(12.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            MessageContextMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                onCopy = onCopy,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssistantMessage(
    content: String,
    source: ResponseSource? = null,
    toolsUsed: List<ToolUsage> = emptyList(),
    tokenUsage: TokenUsage? = null,
    onCopy: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isError = content.startsWith("Error:")
    var menuExpanded by remember { mutableStateOf(false) }

    if (isError) {
        Box {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { menuExpanded = true },
                )
            ) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
            MessageContextMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                onCopy = onCopy,
                onRegenerate = onRegenerate,
            )
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { menuExpanded = true },
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                if (source != null) {
                    SourceBand(source = source, toolsUsed = toolsUsed, tokenUsage = tokenUsage)
                }
                SelectionContainer {
                val isDarkTheme = isSystemInDarkTheme()
                val highlightsBuilder = remember(isDarkTheme) {
                    Highlights.Builder().theme(SyntaxThemes.monokai(darkMode = isDarkTheme))
                }
                val noBullet = BulletHandler { _, _, _, _, _ -> "" }
                CompositionLocalProvider(LocalBulletListHandler provides noBullet) {
                    Markdown(
                        content = content,
                        colors = markdownColor(
                            codeBackground = MaterialTheme.colorScheme.surfaceVariant,
                            inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        typography = markdownTypography(
                            h1 = MaterialTheme.typography.titleLarge,
                            h2 = MaterialTheme.typography.titleMedium,
                            h3 = MaterialTheme.typography.titleSmall,
                            h4 = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            h5 = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            h6 = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            paragraph = MaterialTheme.typography.bodyMedium,
                            bullet = MaterialTheme.typography.bodyMedium,
                            list = MaterialTheme.typography.bodyMedium,
                            code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        ),
                        padding = markdownPadding(
                            block = 8.dp,
                            list = 8.dp,
                            listItemTop = 6.dp,
                            listItemBottom = 6.dp,
                            listIndent = 8.dp,
                            codeBlock = PaddingValues(12.dp),
                            blockQuote = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ),
                        dimens = markdownDimens(
                            codeBackgroundCornerSize = 8.dp,
                            blockQuoteThickness = 3.dp,
                        ),
                        components = markdownComponents(
                            codeBlock = {
                                MarkdownHighlightedCodeBlock(
                                    content = it.content,
                                    node = it.node,
                                    highlightsBuilder = highlightsBuilder,
                                    showHeader = true,
                                )
                            },
                            codeFence = { model ->
                                val firstLine = model.content.lineSequence().firstOrNull { it.startsWith("```") }
                                val lang = firstLine?.removePrefix("```")?.trim()?.lowercase()?.ifEmpty { null }
                                val isSupported = lang != null && SyntaxLanguage.getByName(lang) != null
                                MarkdownHighlightedCodeFence(
                                    content = model.content,
                                    node = model.node,
                                    highlightsBuilder = if (isSupported) highlightsBuilder else Highlights.Builder(),
                                    showHeader = true,
                                )
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            }
            MessageContextMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                onCopy = onCopy,
                onRegenerate = onRegenerate,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceBand(
    source: ResponseSource,
    toolsUsed: List<ToolUsage>,
    tokenUsage: TokenUsage? = null,
    modifier: Modifier = Modifier
) {
    val sourceLabel = when (source) {
        ResponseSource.LOCAL -> "local"
        ResponseSource.MCP -> "mcp"
        ResponseSource.LLM -> "llm"
        ResponseSource.MIXED -> "local + mcp"
    }
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

    Column(modifier = modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = sourceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
            if (tokenUsage != null) {
                Spacer(modifier.width(6.dp))
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                )
                Spacer(modifier.width(6.dp))
                Text(
                    text = stringResource(Res.string.provider_tokens, tokenUsage.formatTotal()),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    modifier = Modifier.semantics {
                        contentDescription = "${tokenUsage.totalTokens} tokens"
                    },
                )
            }
        }
        if (toolsUsed.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                toolsUsed.forEach { tool ->
                    ToolCallIndicator(tool = tool)
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Visual read vs write/action indicator for a tool invoked in chat.
 * Purely presentational — confirmation gating stays on Tool.isDestructive in the engine.
 */
@Composable
fun ToolCallIndicator(
    tool: ToolUsage,
    modifier: Modifier = Modifier,
) {
    val isWrite = tool.isDestructive
    val icon = if (isWrite) Icons.Default.Edit else Icons.Default.Visibility
    val tint = if (isWrite) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    }
    val label = if (isWrite) {
        stringResource(Res.string.cd_tool_write, tool.name)
    } else {
        stringResource(Res.string.cd_tool_read, tool.name)
    }
    val tag = if (isWrite) "badge_tool_write" else "badge_tool_read"

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isWrite) {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
        modifier = modifier
            .testTag(tag)
            .semantics { contentDescription = label },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier.width(4.dp))
            Text(
                text = tool.name,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MessageContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCopy: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
    ) {
        if (onCopy != null) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.chat_action_copy)) },
                onClick = { onCopy(); onDismiss() },
                leadingIcon = {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                },
                modifier = Modifier.testTag("menu_copy"),
            )
        }
        if (onRegenerate != null) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.chat_action_regenerate)) },
                onClick = { onRegenerate(); onDismiss() },
                leadingIcon = {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                },
                modifier = Modifier.testTag("menu_regenerate"),
            )
        }
    }
}
