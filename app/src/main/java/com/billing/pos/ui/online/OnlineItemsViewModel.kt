package com.billing.pos.ui.online

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.billing.pos.customer.OnlineCatalogUpload
import com.billing.pos.data.Item
import com.billing.pos.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An item as shown on the Online Items screen: its live stock and (first) product photo, if any. */
data class OnlineItemRow(val item: Item, val stock: Double, val photoPath: String?)

class OnlineItemsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val rows: StateFlow<List<OnlineItemRow>> =
        kotlinx.coroutines.flow.combine(repo.items, repo.stockByName, repo.itemAttachments) { list, byName, attachments ->
            val photoByItem = attachments.asSequence()
                .filter { it.kind == "PHOTO" }
                .groupBy { it.itemId }
                .mapValues { (_, atts) -> atts.minByOrNull { it.id }?.path }
            list.map { item ->
                val key = item.name.lowercase()
                OnlineItemRow(item, item.openingStock + (byName[key] ?: 0.0), photoByItem[item.id])
            }.sortedByDescending { it.item.id }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uploading = MutableStateFlow(false)
    val uploading: StateFlow<Boolean> = _uploading

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun messageShown() { _message.value = null }

    fun setOnline(item: Item, online: Boolean) {
        viewModelScope.launch { repo.updateItem(item.copy(isOnline = online)) }
    }

    fun setOfferPrice(item: Item, price: Double) {
        viewModelScope.launch { repo.updateItem(item.copy(onlineOfferPrice = price)) }
    }

    fun upload() {
        val online = rows.value.map { it.item }.filter { it.isOnline }
        if (online.isEmpty()) { _message.value = "Mark at least one item online first"; return }
        if (_uploading.value) return
        viewModelScope.launch {
            _uploading.value = true
            when (val result = OnlineCatalogUpload.upload(getApplication(), online)) {
                is OnlineCatalogUpload.Result.Ok -> _message.value = "Uploaded ${result.count} item(s)"
                is OnlineCatalogUpload.Result.Failed -> _message.value = "Upload failed: ${result.message}"
            }
            _uploading.value = false
        }
    }
}
