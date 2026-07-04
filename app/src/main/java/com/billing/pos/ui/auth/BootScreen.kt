package com.billing.pos.ui.auth

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.auth.Session
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Repository
import kotlinx.coroutines.launch

class BootViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    private val prefs = AppPrefs(app)

    fun restore(onResolved: (loggedIn: Boolean) -> Unit) {
        viewModelScope.launch {
            repo.ensureDefaults()
            val id = prefs.loggedInUserId
            val user = if (id >= 0) repo.userById(id) else null
            if (user != null) {
                Session.login(user)
                onResolved(true)
            } else {
                prefs.clearSession()
                onResolved(false)
            }
        }
    }
}

/** Splash that restores a persisted session, then routes to billing or login. */
@Composable
fun BootScreen(
    onResolved: (loggedIn: Boolean) -> Unit,
    vm: BootViewModel = viewModel()
) {
    LaunchedEffect(Unit) { vm.restore(onResolved) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
