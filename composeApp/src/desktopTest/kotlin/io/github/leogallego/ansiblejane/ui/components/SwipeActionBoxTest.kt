package io.github.leogallego.ansiblejane.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SwipeActionBoxTest {

    @Test
    fun exposes_swipe_container_test_tag_and_keeps_click() = runComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                SwipeActionBox(
                    endToStart = SwipeAction(
                        icon = Icons.Filled.Star,
                        label = "Favorite",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        onAction = {},
                        removesItem = false,
                    ),
                    testTag = "swipe_template_1",
                ) {
                    TextButton(onClick = { clicked = true }) {
                        Text("Deploy App")
                    }
                }
            }
        }
        waitForIdle()

        onNodeWithTag("swipe_template_1").assertIsDisplayed()
        onNodeWithText("Deploy App").performClick()
        waitForIdle()
        assertTrue(clicked)
    }

    @Test
    fun swipe_left_invokes_end_to_start_action_without_removing() = runComposeUiTest {
        var actionCount = 0
        setContent {
            MaterialTheme {
                SwipeActionBox(
                    endToStart = SwipeAction(
                        icon = Icons.Filled.Star,
                        label = "Favorite",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        onAction = { actionCount++ },
                        removesItem = false,
                    ),
                    testTag = "swipe_template_1",
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Deploy App", modifier = Modifier.fillMaxWidth())
                }
            }
        }
        waitForIdle()

        onNodeWithTag("swipe_template_1").performTouchInput { swipeLeft() }
        waitForIdle()

        assertEquals(1, actionCount)
        // Snap-back keeps content available for further interaction.
        onNodeWithText("Deploy App").assertIsDisplayed()
    }

    @Test
    fun swipe_left_invokes_removing_action() = runComposeUiTest {
        var dismissed = false
        setContent {
            MaterialTheme {
                SwipeActionBox(
                    endToStart = SwipeAction(
                        icon = Icons.Filled.Delete,
                        label = "Dismiss",
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        onAction = { dismissed = true },
                        removesItem = true,
                    ),
                    testTag = "swipe_notification_1",
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Pending approval", modifier = Modifier.fillMaxWidth())
                }
            }
        }
        waitForIdle()

        onNodeWithTag("swipe_notification_1").performTouchInput { swipeLeft() }
        waitForIdle()

        assertTrue(dismissed)
    }
}
