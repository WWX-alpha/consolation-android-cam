package org.centennialoss.consolation.lut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class CubeLutTest {

    private fun parse(text: String): CubeLut =
        CubeLut.parse(ByteArrayInputStream(text.toByteArray()))

    @Test
    fun parsesTwoByTwoLutWithRedVaryingFastest() {
        val lut = parse(
            """
            # synthetic 2³ LUT
            TITLE "unit test"

            LUT_3D_SIZE 2
            DOMAIN_MIN 0.0 0.0 0.0
            DOMAIN_MAX 1.0 1.0 1.0
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            1.0 1.0 1.0
            """.trimIndent(),
        )

        assertEquals(2, lut.size)
        assertEquals(8, lut.data.size)
        // Texel layout is r | g << 8 | b << 16 | a << 24; 1.0 samples round to 0xFF.
        assertEquals(0xFF000000.toInt(), lut.data[0])
        assertEquals(0xFF0000FF.toInt(), lut.data[1])
        assertEquals(0xFF00FF00.toInt(), lut.data[2])
        assertEquals(0xFF00FFFF.toInt(), lut.data[3])
        assertEquals(0xFFFF0000.toInt(), lut.data[4])
        assertEquals(0xFFFF00FF.toInt(), lut.data[5])
        assertEquals(0xFFFFFF00.toInt(), lut.data[6])
        assertEquals(0xFFFFFFFF.toInt(), lut.data[7])
    }

    @Test
    fun clampsOutOfRangeSamplesToEightBit() {
        val lut = parse(
            """
            LUT_3D_SIZE 2
            2.0 -1.0 0.5
            0.0 0.0 0.0
            0.0 0.0 0.0
            0.0 0.0 0.0
            0.0 0.0 0.0
            0.0 0.0 0.0
            0.0 0.0 0.0
            0.0 0.0 0.0
            """.trimIndent(),
        )

        assertEquals(0xFF8000FF.toInt(), lut.data[0])
    }

    @Test
    fun rejectsOneDimensionalLut() {
        try {
            parse("LUT_1D_SIZE 1024")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("1D LUT"))
        }
    }

    @Test
    fun rejectsShortData() {
        try {
            parse(
                """
                LUT_3D_SIZE 2
                0.0 0.0 0.0
                1.0 0.0 0.0
                0.0 1.0 0.0
                """.trimIndent(),
            )
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("不足"))
        }
    }

    @Test
    fun rejectsExtraData() {
        val rows = (0 until 9).joinToString("\n") { "0.0 0.0 0.0" }
        try {
            parse("LUT_3D_SIZE 2\n$rows")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("超过"))
        }
    }

    @Test
    fun rejectsDataBeforeSize() {
        try {
            parse("0.0 0.0 0.0")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("LUT_3D_SIZE"))
        }
    }

    @Test
    fun parsesCanonSizedThirtyThreeCubeWithoutOverrun() {
        val size = 33
        val rows = buildString {
            append("LUT_3D_SIZE $size\n")
            for (b in 0 until size) {
                for (g in 0 until size) {
                    for (r in 0 until size) {
                        append((r / 32f)).append(' ').append(g / 32f).append(' ').append(b / 32f).append('\n')
                    }
                }
            }
        }
        val lut = parse(rows)

        assertEquals(size, lut.size)
        assertEquals(size * size * size, lut.data.size)
        assertEquals(0xFF000000.toInt(), lut.data.first())
        assertEquals(0xFFFFFFFF.toInt(), lut.data.last())
        // Red varies fastest: the second entry is r=1 for the same g/b.
        assertEquals(0xFF000008.toInt(), lut.data[1])
    }
}
