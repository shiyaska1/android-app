package com.billing.pos.ui.common

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.billing.pos.data.DownloadSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Same as [rememberPdfDownloader], for an .xlsx: runs [produce] (which builds the file, off the
 * main thread) and saves the result into the public Downloads folder, requesting storage
 * permission if needed.
 */
@Composable
fun rememberXlsxDownloader(onMessage: (String) -> Unit): (produce: () -> File) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<(() -> File)?>(null) }

    fun run(produce: () -> File) {
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                val f = produce()
                if (DownloadSaver.save(context, f, f.name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) f.name else null
            }
            onMessage(if (name != null) "Saved to Downloads: $name" else "Could not save Excel file")
        }
    }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val prod = pending; pending = null
        if (granted && prod != null) run(prod) else onMessage("Storage permission denied")
    }

    return { produce ->
        if (DownloadSaver.needsLegacyPermission() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) { pending = produce; perm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        else run(produce)
    }
}
