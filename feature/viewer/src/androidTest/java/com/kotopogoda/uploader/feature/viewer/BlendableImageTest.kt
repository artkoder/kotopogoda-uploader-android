package com.kotopogoda.uploader.feature.viewer

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test

class BlendableImageTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun displayedUriSwitchesToEnhancedWhenStrengthReachesOne() {
        val baseUri = Uri.parse("file:///base.jpg")
        val enhancedUri = Uri.parse("file:///enhanced.jpg")
        val tag = "blendable_under_test"

        val blendState = mutableFloatStateOf(0f)

        composeRule.setContent {
            var blend by remember { blendState }
            BlendableImage(
                baseUri = baseUri,
                enhancedUri = enhancedUri,
                blendFactor = blend,
                modifier = Modifier.testTag(tag)
            )
        }

        composeRule.onNodeWithTag(tag)
            .assert(SemanticsMatcher.expectValue(BlendDisplayedUriKey, baseUri.toString()))

        composeRule.runOnIdle {
            blendState.floatValue = 1f
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithTag(tag)
            .assert(SemanticsMatcher.expectValue(BlendDisplayedUriKey, enhancedUri.toString()))
    }
}
