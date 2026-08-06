package com.billing.pos.ui.messages

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.customer.OrderStatusPush
import com.billing.pos.customer.ShopMessagesFetch
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.ShopMessage
import com.billing.pos.ui.billing.collectAsStateSafe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One row in the "Messages" list — every message with this customer collapsed to its latest,
 *  plus how many incoming ones are still unread. */
data class ChatThread(
    val customerPhone: String,
    val customerName: String,
    val lastText: String,
    val lastAt: Long,
    val unreadCount: Int
)

class ShopMessagesViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).shopMessageDao()

    val messages: StateFlow<List<ShopMessage>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    /** Collapses the flat message list into one row per customer, latest message on top. */
    fun threads(all: List<ShopMessage>): List<ChatThread> =
        all.groupBy { it.customerPhone }.map { (phone, msgs) ->
            val last = msgs.maxByOrNull { it.sentAt }!!
            ChatThread(
                customerPhone = phone,
                customerName = msgs.firstOrNull { it.customerName.isNotBlank() }?.customerName?.ifBlank { phone } ?: phone,
                lastText = last.text,
                lastAt = last.sentAt,
                unreadCount = msgs.count { it.direction == "IN" && !it.read }
            )
        }.sortedByDescending { it.lastAt }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            ShopMessagesFetch.fetch(getApplication())
            _refreshing.value = false
        }
    }

    fun markThreadRead(phone: String) {
        viewModelScope.launch { dao.markReadForCustomer(phone) }
    }

    fun send(phone: String, name: String, text: String) {
        if (text.isBlank() || _sending.value) return
        viewModelScope.launch {
            _sending.value = true
            OrderStatusPush.push(getApplication(), customerPhone = phone, orderId = "", message = text, customerName = name)
            _sending.value = false
        }
    }
}

/** Shop owner's chat inbox: every customer who has exchanged a message, grouped with the latest
 *  one on top and an unread badge — tap a row to open the full thread and reply. [initialPhone]
 *  (set when opened from a "new message" notification tap, see [com.billing.pos.MainActivity])
 *  jumps straight into that customer's thread instead of showing the list first. */
@Composable
fun ShopMessagesScreen(
    onBack: () -> Unit,
    initialPhone: String? = null,
    vm: ShopMessagesViewModel = viewModel()
) {
    val messages by vm.messages.collectAsStateSafe()
    val refreshing by vm.refreshing.collectAsStateSafe()
    var selectedPhone by rememberSaveable { mutableStateOf(initialPhone) }

    LaunchedEffect(Unit) { vm.refresh() }

    val phone = selectedPhone
    if (phone != null) {
        ThreadScreen(
            phone = phone,
            messages = remember(messages, phone) { messages.filter { it.customerPhone == phone }.sortedBy { it.sentAt } },
            vm = vm,
            onBack = { selectedPhone = null }
        )
    } else {
        val threads = remember(messages) { vm.threads(messages) }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Messages") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    },
                    actions = {
                        IconButton(onClick = { vm.refresh() }) {
                            if (refreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                            }
                        }
                    }
                )
            }
        ) { pad ->
            if (threads.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                    Text("No messages yet.", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                    items(threads, key = { it.customerPhone }) { thread ->
                        ListItem(
                            headlineContent = { Text(thread.customerName, fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text(thread.lastText, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = {
                                if (thread.unreadCount > 0) Badge { Text("${thread.unreadCount}") }
                            },
                            modifier = Modifier.fillMaxWidth().clickable { selectedPhone = thread.customerPhone }
                        )
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadScreen(
    phone: String,
    messages: List<ShopMessage>,
    vm: ShopMessagesViewModel,
    onBack: () -> Unit
) {
    val sending by vm.sending.collectAsStateSafe()
    var text by rememberSaveable { mutableStateOf("") }
    val name = messages.firstOrNull { it.customerName.isNotBlank() }?.customerName?.ifBlank { phone } ?: phone
    val listState = rememberLazyListState()

    LaunchedEffect(phone) { vm.markThreadRead(phone) }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text("Message") },
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        val toSend = text.trim()
                        if (toSend.isNotBlank()) { vm.send(phone, name, toSend); text = "" }
                    },
                    enabled = !sending && text.isNotBlank()
                ) {
                    if (sending) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)
        ) {
            items(messages, key = { it.id }) { m ->
                val fromShop = m.direction == "OUT"
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (fromShop) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        color = if (fromShop) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column {
                            Text(m.text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                            Text(
                                SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(m.sentAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
