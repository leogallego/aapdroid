package io.github.leogallego.ansiblejane.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * One-sided (or two-sided) swipe action wrapper around Material 3 [SwipeToDismissBox].
 *
 * Set [SwipeAction.removesItem] to false for toggle-style actions (favorite, open details)
 * so the row snaps back after the gesture. True removes/settles the row (e.g. dismiss).
 */
data class SwipeAction(
    val icon: ImageVector,
    val label: String,
    val containerColor: Color,
    val contentColor: Color,
    val onAction: () -> Unit,
    val removesItem: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeActionBox(
    modifier: Modifier = Modifier,
    endToStart: SwipeAction? = null,
    startToEnd: SwipeAction? = null,
    testTag: String? = null,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    val action = endToStart ?: return@rememberSwipeToDismissBoxState false
                    action.onAction()
                    action.removesItem
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    val action = startToEnd ?: return@rememberSwipeToDismissBoxState false
                    action.onAction()
                    action.removesItem
                }
                SwipeToDismissBoxValue.Settled -> true
            }
        }
    )

    val a11y = buildString {
        endToStart?.let { append("Swipe left: ${it.label}. ") }
        startToEnd?.let { append("Swipe right: ${it.label}.") }
    }.trim()

    SwipeToDismissBox(
        state = state,
        modifier = modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .then(
                if (a11y.isNotEmpty()) {
                    Modifier.semantics { contentDescription = a11y }
                } else {
                    Modifier
                }
            ),
        enableDismissFromEndToStart = endToStart != null,
        enableDismissFromStartToEnd = startToEnd != null,
        backgroundContent = {
            val direction = state.dismissDirection
            val action = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> endToStart
                SwipeToDismissBoxValue.StartToEnd -> startToEnd
                else -> null
            }
            if (action != null) {
                SwipeActionBackground(
                    action = action,
                    alignEnd = direction == SwipeToDismissBoxValue.EndToStart,
                )
            }
        },
        content = { content() },
    )
}

@Composable
private fun SwipeActionBackground(
    action: SwipeAction,
    alignEnd: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(action.containerColor)
            .padding(horizontal = 24.dp),
        contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!alignEnd) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = null,
                    tint = action.contentColor,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = action.contentColor,
                )
            } else {
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = action.contentColor,
                )
                Icon(
                    imageVector = action.icon,
                    contentDescription = null,
                    tint = action.contentColor,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
