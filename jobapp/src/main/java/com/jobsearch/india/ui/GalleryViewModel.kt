package com.jobsearch.india.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jobsearch.india.data.ImageListRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val IMAGES_PER_PAGE = 20

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ImageListRepository(app)

    private val _sourceUrl = MutableStateFlow(repo.sourceUrl)
    val sourceUrl: StateFlow<String> = _sourceUrl.asStateFlow()

    private val _imageUrls = MutableStateFlow(repo.cachedImageUrls())
    val imageUrls: StateFlow<List<String>> = _imageUrls.asStateFlow()

    private val _page = MutableStateFlow(0)
    val page: StateFlow<Int> = _page.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        if (_sourceUrl.value.isNotBlank()) refresh()
    }

    fun setSourceUrl(url: String) {
        repo.sourceUrl = url
        _sourceUrl.value = url
        _page.value = 0
        refresh()
    }

    fun goToPage(index: Int) {
        _page.value = index
    }

    fun refresh() {
        val url = _sourceUrl.value
        if (url.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            repo.fetchImageUrls(url)
                .onSuccess {
                    _imageUrls.value = it
                    _page.value = 0
                }
                .onFailure {
                    _errorMessage.value = it.message ?: "Could not load job posts"
                }
            _isLoading.value = false
        }
    }
}
