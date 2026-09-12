// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.pdf

/** Memory-aware whole-image decode budget for map albums. */
data class ImageDecodePolicy(
    val maxPixels: Long,
    val reducedForMemory: Boolean,
) {
    companion object {
        private const val STANDARD_PIXELS = 12_000_000L
        private const val CONSTRAINED_PIXELS = 8_000_000L
        private const val LOW_MEMORY_PIXELS = 6_000_000L

        fun forDevice(memoryClassMb: Long, isLowRamDevice: Boolean): ImageDecodePolicy = when {
            isLowRamDevice || memoryClassMb <= 128L ->
                ImageDecodePolicy(LOW_MEMORY_PIXELS, reducedForMemory = true)
            memoryClassMb < 256L ->
                ImageDecodePolicy(CONSTRAINED_PIXELS, reducedForMemory = true)
            else ->
                ImageDecodePolicy(STANDARD_PIXELS, reducedForMemory = false)
        }
    }

    val maxBitmapBytes: Long
        get() = maxPixels * 4L
}
