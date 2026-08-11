package com.gastos.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageSamplingTest {
    @Test
    fun `small images keep native decode sample`() {
        assertEquals(1, calculateDecodeSampleSize(width = 1800, height = 1200, maxDimension = 1800))
    }

    @Test
    fun `large images use the largest useful power of two sample`() {
        assertEquals(2, calculateDecodeSampleSize(width = 4000, height = 3000, maxDimension = 1800))
        assertEquals(4, calculateDecodeSampleSize(width = 8000, height = 6000, maxDimension = 1800))
    }
}
