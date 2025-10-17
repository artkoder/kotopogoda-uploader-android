package com.kotopogoda.uploader.feature.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@MediumTest
class OnboardingMediaPermissionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun mediaPermissionScreenShowsBothActions() {
        composeRule.setContent {
            MediaPermissionMissingContent(
                onRequestPermission = {},
                onSelectFolder = {}
            )
        }

        val grantLabel = composeRule.activity.getString(R.string.media_permission_grant)
        val safLabel = composeRule.activity.getString(R.string.media_permission_select_folder)

        composeRule.onNodeWithText(grantLabel).assertExists()
        composeRule.onNodeWithText(safLabel).assertExists()
    }

    @Test
    fun safActionInvokesFolderPicker() {
        var launchCount = 0

        composeRule.setContent {
            MediaPermissionMissingContent(
                onRequestPermission = {},
                onSelectFolder = { launchCount++ }
            )
        }

        val safLabel = composeRule.activity.getString(R.string.media_permission_select_folder)
        composeRule.onNodeWithText(safLabel).performClick()

        composeRule.runOnIdle {
            assertTrue(launchCount == 1)
        }
    }
}
