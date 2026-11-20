package org.szabadpdf.core.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import org.junit.Rule
import org.junit.Test

class TipTest {

    @get:Rule
    val compose = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun hoveringShowsTheTip() {
        compose.setContent {
            MaterialTheme {
                Tip("Find in document") {
                    IconButton(onClick = {}) { Icon(Icons.Filled.Search, "Search") }
                }
            }
        }
        compose.onNodeWithText("Find in document").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search").performMouseInput { moveTo(center) }
        compose.waitUntil(3000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Find in document")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Search").performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(-200f, -200f)) }
        compose.waitUntil(3000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Find in document")).fetchSemanticsNodes().isEmpty() }
    }
}
