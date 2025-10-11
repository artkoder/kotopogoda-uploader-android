@file:OptIn(ExperimentalFoundationApi::class)

package com.kotopogoda.uploader.feature.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotopogoda.uploader.core.data.photo.PhotoItem
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.snapshotFlow

@Composable
fun ViewerRoute(
    onBack: () -> Unit,
    viewModel: ViewerViewModel = hiltViewModel()
) {
    val photos by viewModel.photos.collectAsState()
    val pagerScrollEnabled by viewModel.isPagerScrollEnabled.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()

    ViewerScreen(
        photos = photos,
        currentIndex = currentIndex,
        isPagerScrollEnabled = pagerScrollEnabled,
        onBack = onBack,
        onPageChanged = viewModel::setCurrentIndex,
        onZoomStateChanged = { atBase -> viewModel.setPagerScrollEnabled(atBase) }
    )
}

@Composable
private fun ViewerScreen(
    photos: List<PhotoItem>,
    currentIndex: Int,
    isPagerScrollEnabled: Boolean,
    onBack: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onZoomStateChanged: (Boolean) -> Unit
) {
    BackHandler(onBack = onBack)

    if (photos.isEmpty()) {
        ViewerEmptyState()
        return
    }

    var rememberedIndex by rememberSaveable(currentIndex) { mutableIntStateOf(currentIndex) }
    val pagerState = rememberPagerState(initialPage = rememberedIndex, pageCount = { photos.size })

    LaunchedEffect(currentIndex, photos.size) {
        val clamped = currentIndex.coerceIn(0, photos.lastIndex)
        if (clamped != rememberedIndex) {
            rememberedIndex = clamped
        }
        if (photos.isNotEmpty() && pagerState.currentPage != clamped) {
            pagerState.scrollToPage(clamped)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .collectLatest { page ->
                if (rememberedIndex != page) {
                    rememberedIndex = page
                }
                onPageChanged(page)
            }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        VerticalPager(
            state = pagerState,
            userScrollEnabled = isPagerScrollEnabled,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = photos[page]
            ZoomableImage(
                uri = item.uri,
                modifier = Modifier.fillMaxSize(),
                onZoomChanged = onZoomStateChanged
            )
        }
    }
}

@Composable
private fun ViewerEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(id = R.string.viewer_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(id = R.string.viewer_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}
