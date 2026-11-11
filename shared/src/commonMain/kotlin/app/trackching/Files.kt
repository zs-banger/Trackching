package app.trackching

import androidx.compose.runtime.Composable

/**
 * Returns a function save(name, text). It opens the phone's save screen with [name] filled in,
 * writes [text] to the place picked, then calls onDone(true). Cancel or a failed write calls onDone(false).
 */
@Composable
expect fun rememberSaveFile(mime: String, onDone: (saved: Boolean) -> Unit): (name: String, text: String) -> Unit

/**
 * Returns a function open(). It opens the phone's file picker and calls onText with the file's text,
 * or with null if the file can't be read. Cancel calls nothing.
 */
@Composable
expect fun rememberOpenFile(onText: (String?) -> Unit): () -> Unit
