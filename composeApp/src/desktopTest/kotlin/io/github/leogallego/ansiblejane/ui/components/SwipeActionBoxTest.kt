package io.github.leogallego.ansiblejane.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
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
}
