package com.gastos.feature.backup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.delay

@Composable
internal fun rememberTimedPasswordVisualTransformation(
    value: String,
    revealDurationMillis: Long = PASSWORD_REVEAL_DURATION_MS
): VisualTransformation {
    var previousLength by remember { mutableIntStateOf(value.length) }
    var revealedIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(value) {
        revealedIndex = if (value.length > previousLength) value.lastIndex else -1
        previousLength = value.length
        if (revealedIndex >= 0) {
            delay(revealDurationMillis)
            revealedIndex = -1
        }
    }
    return remember(revealedIndex) { LastCharacterVisiblePasswordTransformation(revealedIndex) }
}

internal class LastCharacterVisiblePasswordTransformation(
    private val revealedIndex: Int
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val masked = buildString(text.length) {
            text.forEachIndexed { index, character ->
                append(if (index == revealedIndex) character else PASSWORD_MASK)
            }
        }
        return TransformedText(AnnotatedString(masked), OffsetMapping.Identity)
    }
}

private const val PASSWORD_REVEAL_DURATION_MS = 1_000L
private const val PASSWORD_MASK = '*'
