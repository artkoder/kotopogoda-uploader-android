package com.kotopogoda.uploader.feature.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderSelectedContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun changeFolderButtonDisabledDuringScan() {
        var changeFolderRequests = 0

        composeTestRule.setContent {
            MaterialTheme {
                FolderSelectedContent(
                    folderUri = "content://test/folder",
                    progress = null,
                    photoCount = 0,
                    scanState = OnboardingScanState.InProgress(progress = null),
                    isChangeFolderEnabled = false,
                    onChangeFolder = { changeFolderRequests++ },
                    onStartReview = { _, _ -> },
                    onResetProgress = {},
                    onResetAnchor = {}
                )
            }
        }

        val button = composeTestRule.onNodeWithText("Сменить папку")
        button.assertIsNotEnabled()
        val clickResult = runCatching { button.performClick() }
        assertThat(clickResult.exceptionOrNull()).isNotNull()
        assertThat(changeFolderRequests).isEqualTo(0)
    }
}
