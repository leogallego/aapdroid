package io.github.leogallego.ansiblejane.assistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import io.github.leogallego.ansiblejane.assistant.engine.ResponseSource
import io.github.leogallego.ansiblejane.assistant.engine.ToolUsage
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ToolCallIndicatorTest {

    @Test
    fun shows_read_and_write_indicators() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Column {
                    ToolCallIndicator(tool = ToolUsage(name = "list_hosts"))
                    ToolCallIndicator(tool = ToolUsage(name = "launch_job", isDestructive = true))
                }
            }
        }
        waitForIdle()

        onNodeWithTag("badge_tool_read").assertIsDisplayed()
        onNodeWithTag("badge_tool_write").assertIsDisplayed()
        onNodeWithText("list_hosts").assertIsDisplayed()
        onNodeWithText("launch_job").assertIsDisplayed()
        onNodeWithContentDescription("Read tool: list_hosts").assertIsDisplayed()
        onNodeWithContentDescription("Write tool: launch_job").assertIsDisplayed()
    }

    @Test
    fun assistant_message_source_band_shows_tool_indicators() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AssistantMessage(
                    content = "Found hosts.",
                    source = ResponseSource.LOCAL,
                    toolsUsed = listOf(
                        ToolUsage("list_hosts"),
                        ToolUsage("launch_job", isDestructive = true),
                    ),
                )
            }
        }
        waitForIdle()

        onNodeWithTag("badge_tool_read").assertIsDisplayed()
        onNodeWithTag("badge_tool_write").assertIsDisplayed()
    }
}
