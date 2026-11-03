package org.centennialoss.consolation.lut

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale

/**
 * Parsed 3D LUT: [size]³ texels packed as `r | g << 8 | b << 16 | a << 24` (red in the low
 * byte, matching the native RGBA upload), in .cube row order (red varies fastest).
 */
class CubeLut(
    val size: Int,
    val data: IntArray,
) {
    companion object {
        private const val ALPHA_OPAQUE = 0xFF shl 24
        private const val MIN_SIZE = 2
        private const val MAX_SIZE = 256

        /**
         * Streams one .cube file. Comments (#), blank lines, TITLE and DOMAIN_* are skipped;
         * LUT_1D_SIZE is rejected.
         */
        fun parse(input: InputStream): CubeLut {
            var size = 0
            var data = IntArray(0)
            var filled = 0
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            try {
                var lineNumber = 0
                while (true) {
                    val raw = reader.readLine() ?: break
                    lineNumber++
                    val line = raw.trim()
                    if (line.isEmpty() || line[0] == '#') continue
                    val fields = line.split(' ', '\t').filter { it.isNotEmpty() }
                    when (fields[0].uppercase(Locale.US)) {
                        "TITLE", "DOMAIN_MIN", "DOMAIN_MAX", "LUT_3D_INPUT_RANGE" -> continue
                        "LUT_1D_SIZE", "LUT_1D_INPUT_RANGE" ->
                            throw IllegalArgumentException("1D LUT 不支持，请用 3D LUT")
                        "LUT_3D_SIZE" -> {
                            val parsed = fields.getOrNull(1)?.toIntOrNull()
                                ?: throw IllegalArgumentException("无效的 LUT_3D_SIZE（第 $lineNumber 行: $line）")
                            if (parsed !in MIN_SIZE..MAX_SIZE) {
                                throw IllegalArgumentException(
                                    "LUT_3D_SIZE 需在 $MIN_SIZE..$MAX_SIZE（第 $lineNumber 行: $parsed）",
                                )
                            }
                            if (size != 0) throw IllegalArgumentException("重复的 LUT_3D_SIZE（第 $lineNumber 行）")
                            size = parsed
                            data = IntArray(size * size * size)
                            continue
                        }
                    }
                    if (size == 0) {
                        throw IllegalArgumentException("LUT_3D_SIZE 之前出现数据行（第 $lineNumber 行: $line）")
                    }
                    if (filled >= data.size) {
                        throw IllegalArgumentException("LUT 数据行超过 ${size}³ = ${data.size} 行（第 $lineNumber 行）")
                    }
                    val r = fields.getOrNull(0)?.toFloatOrNull()
                    val g = fields.getOrNull(1)?.toFloatOrNull()
                    val b = fields.getOrNull(2)?.toFloatOrNull()
                    if (r == null || g == null || b == null) {
                        throw IllegalArgumentException("无法解析 LUT 数据行（第 $lineNumber 行: $line）")
                    }
                    data[filled++] = ALPHA_OPAQUE or (toByte(b) shl 16) or (toByte(g) shl 8) or toByte(r)
                }
            } finally {
                reader.close()
            }
            if (size == 0) throw IllegalArgumentException("缺少 LUT_3D_SIZE")
            if (filled != data.size) {
                throw IllegalArgumentException("LUT 数据行不足：需要 ${size}³ = ${data.size} 行，实际 $filled 行")
            }
            return CubeLut(size, data)
        }

        /** Clamps to 0..1 and rounds to 8 bit; NaN maps to 0. */
        private fun toByte(value: Float): Int {
            if (value.isNaN()) return 0
            return (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
    }
}
