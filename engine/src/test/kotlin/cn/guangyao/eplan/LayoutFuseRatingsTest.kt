package cn.guangyao.eplan

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class LayoutFuseRatingsTest {
    private fun box(text: String, x: Float, y: Float, advance: Float = 8.5f, angle: Int = 0) =
        PdfTextBox(text, x, y - 3f, advance, 3f, x, y, angle, 3f, !text.startsWith("("), advance)

    @Test fun denseFuseRowsKeepTheirOwnRatingEvenWhenDeviceIsFarAway() {
        val io = box("IO电源", 1010.11f, 417.387f, 12.75f)
        val safety = box("安全总电源", 1010.11f, 420.846f, 21.26f)
        val page = PdfPageText(164, 1202f, 853f, listOf(io, safety,
            box("(2A)", 1001.61f, 417.387f), box("(6A)", 1001.61f, 420.846f)))
        assertEquals("(2A)IO电源", LayoutFuseRatings.decorate(page, "1FU08", AbFunctionEvidence(io.text, io, 164)).text)
        assertEquals("(6A)安全总电源", LayoutFuseRatings.decorate(page, "1FU07", AbFunctionEvidence(safety.text, safety, 164)).text)
    }

    @Test fun nearbyDifferentRowCannotSupplyMissingRating() {
        val function = box("IO电源", 1010.11f, 417.387f)
        val page = PdfPageText(164, 1202f, 853f, listOf(function, box("(6A)", 1001.61f, 420.846f)))
        assertEquals("IO电源", LayoutFuseRatings.decorate(page, "1FU08", AbFunctionEvidence(function.text, function, 164)).text)
    }

    @Test fun nonFuseNeverGainsCurrentFromNearbySpecification() {
        val function = box("控制电源", 80f, 60f)
        val page = PdfPageText(1, 400f, 600f, listOf(function, box("(2A)", 71.5f, 60f)))
        for (device in listOf("1QF08", "1KM07", "1XS02", "1XT01")) {
            assertEquals("控制电源", LayoutFuseRatings.decorate(page, device, AbFunctionEvidence(function.text, function, 1)).text)
        }
    }

    @Test fun diagonalAndVerticalFunctionLinesUseTheirOwnBaseline() {
        for (angle in listOf(90, 180, 270, 315)) {
            val radians = Math.toRadians(angle.toDouble())
            val function = box("STO", 150f, 150f, 15f, angle)
            val rating = box("(0.5A)", 150f - cos(radians).toFloat() * 12f,
                150f - sin(radians).toFloat() * 12f, 12f, angle)
            val page = PdfPageText(1, 400f, 600f, listOf(function, rating))
            assertEquals("angle $angle", "(0.5A)STO", LayoutFuseRatings.decorate(page, "FU01", AbFunctionEvidence("STO", function, 1)).text)
        }
    }

    @Test fun existingPrefixIsNotDuplicatedAndModelNumbersAreNotRatings() {
        val function = box("(2A)IO电源", 80f, 60f)
        val model = box("RT18-32 2A", 71.5f, 60f)
        val page = PdfPageText(1, 400f, 600f, listOf(function, model))
        assertEquals("(2A)IO电源", LayoutFuseRatings.decorate(page, "1FU08", AbFunctionEvidence(function.text, function, 1)).text)
        assertEquals("IO电源", LayoutFuseRatings.decorate(page, "1FU08", AbFunctionEvidence("IO电源", function, 1)).text)
    }

    @Test fun identicalPositionWithConflictingAmperageDoesNotGuess() {
        val function = box("IO电源", 80f, 60f)
        val page = PdfPageText(1, 400f, 600f, listOf(function,
            box("(2A)", 71.5f, 60f), box("(6A)", 71.5f, 60f)))
        assertEquals("IO电源", LayoutFuseRatings.decorate(page, "1FU08", AbFunctionEvidence(function.text, function, 1)).text)
    }

    @Test fun nativeOverlapKeepsOwnAmpsInAllDirectionsAndDoesNotBorrowAdjacentTriplets() {
        for (angle in listOf(0, 90, 180, 270, 315)) {
            val radians = Math.toRadians(angle.toDouble())
            fun native(text: String, along: Float, sequence: Int, blue: Boolean, across: Float = 0f) =
                box(text, 150f + cos(radians).toFloat() * along - sin(radians).toFloat() * across,
                    150f + sin(radians).toFloat() * along + cos(radians).toFloat() * across, 18f, angle)
                    .copy(blue = blue, sourceObject = sequence, sourceRun = sequence,
                        sourceSequence = sequence, sourceEndSequence = sequence)
            val id = native("-1FU08", -40f, 1, true)
            val amp = native("(2A)", 4.8f, 2, false)
            val caption = native("IO电源", 0f, 3, true)
            val page = PdfPageText(1, 400f, 600f, listOf(id, amp, caption))
            val evidence = AbFunctionEvidence(caption.text, caption, 1)
            assertEquals("angle=$angle", "(2A)IO电源", LayoutFuseRatings.decorate(page, "1FU08", evidence).text)
            assertEquals("another owner", "IO电源", LayoutFuseRatings.decorate(page, "1FU09", evidence).text)
            for (invalid in listOf(amp.copy(blue = true), amp.copy(text = "RT18-32 2A"),
                native("(6A)", 4.8f, 2, false, 3f), native("(6A)", 80f, 2, false))) {
                assertEquals("angle=$angle invalid=$invalid", "IO电源",
                    LayoutFuseRatings.decorate(page.copy(boxes = listOf(id, invalid, caption)), "1FU08", evidence).text)
            }
            assertEquals("missing native identity cannot justify overlap", "IO电源",
                LayoutFuseRatings.decorate(page.copy(boxes = listOf(id.copy(sourceObject = -1), amp, caption)), "1FU08", evidence).text)
        }
    }
}
