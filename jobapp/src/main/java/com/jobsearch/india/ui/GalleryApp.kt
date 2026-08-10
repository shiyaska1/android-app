package com.jobsearch.india.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jobsearch.india.ui.gallery.GalleryScreen
import com.jobsearch.india.ui.gallery.ImageViewerScreen

private const val GALLERY_ROUTE = "gallery"
private const val VIEWER_ROUTE = "viewer"

@Composable
fun GalleryApp() {
    val viewModel: GalleryViewModel = viewModel()
    val navController = rememberNavController()

    val sourceUrl by viewModel.sourceUrl.collectAsState()
    val imageUrls by viewModel.imageUrls.collectAsState()
    val page by viewModel.page.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var selectedImageUrl by remember { mutableStateOf<String?>(null) }

    NavHost(navController = navController, startDestination = GALLERY_ROUTE) {
        composable(GALLERY_ROUTE) {
            GalleryScreen(
                sourceUrl = sourceUrl,
                imageUrls = imageUrls,
                page = page,
                isLoading = isLoading,
                errorMessage = errorMessage,
                onSetSourceUrl = { viewModel.setSourceUrl(it) },
                onGoToPage = { viewModel.goToPage(it) },
                onRefresh = { viewModel.refresh() },
                onImageClick = { url ->
                    selectedImageUrl = url
                    navController.navigate(VIEWER_ROUTE)
                }
            )
        }
        composable(VIEWER_ROUTE) {
            val url = selectedImageUrl
            if (url != null) {
                ImageViewerScreen(imageUrl = url, onBack = { navController.popBackStack() })
            }
        }
    }
}
