package com.gastos.common.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object EssentialLayout {
    val screenPadding: Dp = 16.dp
    val fieldSpacing: Dp = 12.dp
    val smallSpacing: Dp = 8.dp
    val touchTarget: Dp = 48.dp
    val fieldShape: RoundedCornerShape = RoundedCornerShape(12.dp)
    val cardShape: RoundedCornerShape = RoundedCornerShape(16.dp)
}

@Composable
fun essentialFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
    focusedBorderColor = MaterialTheme.colorScheme.primary
)
