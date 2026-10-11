package cn.guangyao.eplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class EplanNativeTextTest {
    @Test fun overlappingNativeSignalRowsRetainAllFifteenStatements() {
        // Independently read from B06 physical page 130. The first group's second line
        // overlaps the next group, and its baseline differs by only 0.308 PDF points.
        val groups = listOf(
            Triple(710.234f, 277.454f, listOf("1：急停辅助输出", "2：安全光栅辅助输出", "3：安全门禁辅助输出",
                "4：三段式开关辅助输出", "5：正常模式辅助输出", "6：维修模式辅助输出", "7：安全复位", "8：备用")),
            Triple(729.392f, 277.147f, listOf("1:冷水机启动", "2：除尘机启动")),
            Triple(742.754f, 277.147f, listOf("1：冷水机报警", "2：除尘机报警")),
            Triple(756.117f, 277.147f, listOf("1：NG缓存D调速电机1", "2：NG缓存D调速电机2", "3：NG缓存D调速电机3")),
        )
        var sequence = 0
        var run = 0
        val glyphs = groups.flatMap { (x, y, labels) -> labels.flatMapIndexed { row, label ->
            glyphs(label, x, y + row * 2.3622f, run++, sequence, font = 2.3622f * .65f, em = 2.3622f)
                .also { sequence += it.size }
        } }
        val native = EplanParsing.joinNativeGlyphs(glyphs)
        assertEquals(groups.flatMap { it.third }, native.map { it.text })
        assertEquals(15, native.size)
        assertEquals(15, native.map { it.sourceRun }.distinct().size)
        assertTrue(native.all { it.sourceEndSequence >= it.sourceSequence })
        // This must exercise the actual corruption mechanism, not just distant labels.
        val legacy = EplanParsing.joinGlyphs(glyphs)
        assertTrue(legacy.any { "输2出" in it.text })
        assertFalse(native.any { "输2出" in it.text })
    }

    @Test fun lettersAcrossTjAndFontChangesStillFormOneCaption() {
        val input = glyphs("IO-", 10f, 20f, 0, 0, objectId = 3, em = 8f) +
            glyphs("LINK", 22f, 20f, 1, 3, objectId = 3, em = 8f) +
            glyphs("电源", 38f, 20f, 2, 7, objectId = 3, em = 8f, font = 6.5f)
        val value = EplanParsing.joinNativeGlyphs(input).single()
        assertEquals("IO-LINK电源", value.text)
        assertEquals(0, value.sourceRun)
        assertEquals(8, value.sourceEndSequence)
    }

    @Test fun individualGlyphTextObjectsDoNotBecomeIndividualLabels() {
        val text = "-1XT01"
        val values = glyphs(text, 10f, 20f, 0, 0).mapIndexed { index, box ->
            box.copy(sourceObject = index, sourceRun = index)
        }
        assertEquals(text, EplanParsing.joinNativeGlyphs(values).single().text)
    }

    @Test fun adjacentChineseSegmentsInSeparateObjectsCanContinue() {
        val values = glyphs("冷水机", 10f, 20f, 0, 0) + glyphs("启动", 34f, 20f, 1, 3)
        assertEquals("冷水机启动", EplanParsing.joinNativeGlyphs(values).single().text)
    }

    @Test fun explicitSpacesSurviveFontAndTextOperationBoundaries() {
        val values = glyphs("IO ", 10f, 20f, 0, 0) + glyphs("POWER", 22f, 20f, 1, 3)
        assertEquals("IO POWER", EplanParsing.joinNativeGlyphs(values).single().text)
    }

    @Test fun touchingNumberedCaptionsRemainIndependentWithoutChangingRangeLabels() {
        val a = glyphs("1:启动", 10f, 20f, 0, 0)
        val b = glyphs("2:报警", a.last().baselineX + a.last().advance, 20f, 1, a.size)
        assertEquals(listOf("1:启动", "2:报警"), EplanParsing.joinNativeGlyphs(a + b).map { it.text })
        val range = glyphs("1-4", 10f, 50f, 2, 0) + glyphs("限位端子", 22f, 50f, 3, 3)
        assertEquals("1-4限位端子", EplanParsing.joinNativeGlyphs(range).single().text)
    }

    @Test fun oneTjWithAJumpDoesNotDefineASingleLabel() {
        val values = glyphs("FIRST", 10f, 20f, 0, 0) + glyphs("SECOND", 100f, 20f, 0, 5)
        assertEquals(listOf("FIRST", "SECOND"), EplanParsing.joinNativeGlyphs(values).map { it.text })
    }

    @Test fun overlappingTextWithinOneTJRetainsNativeSequenceInsteadOfInterleaving() {
        val values = glyphs("1:START", 10f, 20f, 0, 0) + glyphs("2:ALARM", 15f, 20f, 0, 7)
        assertEquals(listOf("1:START", "2:ALARM"), EplanParsing.joinNativeGlyphs(values).map { it.text })
    }

    @Test fun nativeJoiningUsesTheSameGeometryForAllFourAndDiagonalDirections() {
        for (angle in listOf(0, 45, 90, 180, 270, 315)) {
            val values = glyphs("IO-LINK", 100f, 100f, 0, 0, angle = angle).mapIndexed { index, box ->
                box.copy(sourceRun = index, sourceObject = index)
            }
            val label = EplanParsing.joinNativeGlyphs(values).single()
            assertEquals("angle=$angle", "IO-LINK", label.text)
            assertEquals(angle, label.angle)
            assertEquals(28f, label.advance, .001f)
        }
    }

    @Test fun blackRatingIsNotSwallowedIntoBlueCaptionAndDistantDuplicatesRemain() {
        val values = glyphs("(2A)", 10f, 20f, 0, 0).map { it.copy(blue = false) } +
            glyphs("IO电源", 26f, 20f, 1, 4) +
            glyphs("-1XT01", 10f, 50f, 2, 8) + glyphs("-1XT01", 110f, 50f, 3, 14)
        val labels = EplanParsing.joinNativeGlyphs(values)
        assertEquals(listOf("(2A)", "IO电源", "-1XT01", "-1XT01"), labels.map { it.text })
        assertFalse(labels.first().blue)
        assertTrue(labels[1].blue)
    }

    @Test fun nativeOverprintAtSamePhysicalPositionIsOneLabel() {
        val first = glyphs("-1XT01", 10f, 20f, 0, 0)
        val second = glyphs("-1XT01", 10f, 20f, 1, first.size)
        assertEquals("-1XT01", EplanParsing.joinNativeGlyphs(first + second).single().text)
    }

    @Test fun interveningTextPreventsAnUnprovenCrossObjectJoin() {
        val values = glyphs("IO", 10f, 20f, 0, 0) + glyphs("MODEL", 200f, 100f, 1, 2) +
            glyphs("电源", 18f, 20f, 2, 7)
        assertEquals(listOf("IO", "MODEL", "电源"), EplanParsing.joinNativeGlyphs(values).map { it.text })
    }

    @Test fun callersWithoutMetadataRetainTheirLegacyInterpretation() {
        val values = glyphs("STO", 10f, 20f, 0, 0).map {
            it.copy(sourceRun = -1, sourceObject = -1, sourceSequence = -1, sourceEndSequence = -1)
        }
        assertEquals(EplanParsing.joinGlyphs(values), EplanParsing.joinNativeGlyphs(values))
    }

    @Test fun cancellationPropagatesFromNativeReconstruction() {
        assertThrows(IllegalStateException::class.java) {
            EplanParsing.joinNativeGlyphs(glyphs("STOP", 10f, 20f, 0, 0)) { error("cancelled") }
        }
    }

    private fun glyphs(text: String, x: Float, y: Float, run: Int, sequence: Int, objectId: Int = run,
        angle: Int = 0, font: Float = 6f, em: Float = 8f): List<PdfTextBox> {
        val radians = Math.toRadians(angle.toDouble())
        val dx = cos(radians).toFloat(); val dy = sin(radians).toFloat()
        var along = 0f
        return text.mapIndexed { index, char ->
            val advance = if (char.code < 128) em / 2f else em
            val bx = x + along * dx; val by = y + along * dy
            val xs = listOf(bx, bx + advance * dx, bx + font * dy, bx + advance * dx + font * dy)
            val ys = listOf(by, by + advance * dy, by - font * dx, by + advance * dy - font * dx)
            along += advance
            PdfTextBox(char.toString(), xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min(),
                bx, by, angle, font, true, advance, objectId, run, sequence + index)
        }
    }
}
