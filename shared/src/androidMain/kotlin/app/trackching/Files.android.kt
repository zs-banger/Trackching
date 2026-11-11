package app.trackching

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberSaveFile(mime: String, onDone: (saved: Boolean) -> Unit): (name: String, text: String) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        val text = pending
        pending = null
        val saved = uri != null && text != null && runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        }.isSuccess
        onDone(saved)
    }
    return { name, text ->
        pending = text
        launcher.launch(name)
    }
}

@Composable
actual fun rememberOpenFile(onText: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult // cancelled
        onText(runCatching {
            context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        }.getOrNull())
    }
    return { launcher.launch(arrayOf("*/*")) }
}
