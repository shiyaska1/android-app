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
import com.billing.pos.data.License
import com.billing.pos.data.Repository
import kotlinx.coroutines.launch

class BootViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    private val prefs = AppPrefs(app)

    /** Resolves the start route: "register", "license", "billing", or "login". */
    fun resolve(onResolved: (route: String) -> Unit) {
        viewModelScope.launch {
            repo.ensureDefaults()
            when {
                prefs.mobileNumber.isBlank() -> onResolved("register")
                !prefs.licensed && License.trialExpired(prefs.installDateMillis) -> onResolved("license")
                else -> {
                    val id = prefs.loggedInUserId
                    val user = if (id >= 0) repo.userById(id) else null
                    if (user != null) {
                        Session.login(user)
                        onResolved("dashboard")
                    } else {
                        prefs.clearSession()
                        onResolved("login")
                    }
                }
            }
        }
    }
}

/** Splash that checks trial/license, restores a session, then routes onward. */
@Composable
fun BootScreen(
    onResolved: (route: String) -> Unit,
    vm: BootViewModel = viewModel()
) {
    LaunchedEffect(Unit) { vm.resolve(onResolved) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
