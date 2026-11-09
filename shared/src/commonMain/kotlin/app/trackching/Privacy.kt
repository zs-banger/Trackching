package app.trackching

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf

/** What every hidden amount looks like. */
const val MaskedAmount = "₱ ••••"

/** True while amounts are hidden. AppShell provides it; any screen can read it. */
val LocalAmountsHidden = compositionLocalOf { false }

/** The text, or ₱ •••• when hiding. Pure, so it's easy to test. */
fun masked(text: String, hidden: Boolean): String = if (hidden) MaskedAmount else text

/** Use this for every amount on screen. */
@Composable
@ReadOnlyComposable
fun shown(text: String): String = masked(text, LocalAmountsHidden.current)
