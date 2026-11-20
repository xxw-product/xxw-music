package com.musicdl.android

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

internal data class CoverPalette(val background: Color, val surface: Color, val accent: Color, val seed: Color = accent)

/** Quantize a small sample; ignore near-black pixels rather than tint everything black. */
internal fun coverPalette(bitmap: Bitmap?): CoverPalette {
    var selected = Color(0xFF6F747D)
    if (bitmap != null) {
        val buckets = mutableMapOf<Int, Int>()
        for (y in 0 until bitmap.height step maxOf(1, bitmap.height / 40)) {
            for (x in 0 until bitmap.width step maxOf(1, bitmap.width / 40)) {
                val pixel = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(pixel); val g = android.graphics.Color.green(pixel); val b = android.graphics.Color.blue(pixel)
                if (maxOf(r, g, b) < 35 || android.graphics.Color.alpha(pixel) < 128) continue
                val key = (r / 32 shl 6) or (g / 32 shl 3) or (b / 32)
                buckets[key] = (buckets[key] ?: 0) + 1
            }
        }
        buckets.maxByOrNull { it.value }?.key?.let { key ->
            selected = Color(((key shr 6) * 32 + 16) / 255f, (((key shr 3) and 7) * 32 + 16) / 255f, ((key and 7) * 32 + 16) / 255f)
        }
    }
    return CoverPalette(lerp(Color.Black, selected, 0.22f), lerp(Color.Black, selected, 0.48f), lerp(selected, Color.White, 0.65f), selected)
}
