package com.gastos.feature.backup

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

class TimedPasswordVisualTransformationTest {
    @Test
    fun `only selected character remains visible`() {
        val transformation = LastCharacterVisiblePasswordTransformation(revealedIndex = 2)

        val transformed = transformation.filter(AnnotatedString("clave")).text.text

        assertEquals("**a**", transformed)
    }

    @Test
    fun `all characters are masked after reveal expires`() {
        val transformation = LastCharacterVisiblePasswordTransformation(revealedIndex = -1)

        val transformed = transformation.filter(AnnotatedString("clave")).text.text

        assertEquals("*****", transformed)
    }
}
