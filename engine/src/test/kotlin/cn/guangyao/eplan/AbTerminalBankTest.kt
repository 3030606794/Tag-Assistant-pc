package cn.guangyao.eplan

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

class AbTerminalBankTest {
    @Test fun onePinAndOneCaptionStayWithTheirTerminalWhenTheMiddleLinkIsMissing() {
        val base = sample()
        val neighbor = base.devices.last().copy(text = "-1FU09")
        val caption = base.page.boxes[2].copy(text = "FFU")
        val boxes = base.page.boxes.take(4).map { if (it == base.page.boxes[2]) caption else it } + neighbor
        val original = Sample(base.page.copy(boxes = boxes), listOf(base.devices.first(), neighbor),
            listOf(base.fields.first(), base.fields.last()))
        for (rotation in listOf(0, 90, 180, 270)) {
            val sample = original.rotate(rotation)
            val mapped = AbLayoutAnalysis.associateFunctions(sample.page, sample.devices, linkAreas = sample.fields)
            assertEquals("rotation=$rotation", listOf("FFU"), mapped[sample.devices.first()].orEmpty().map { it.text })
            assertTrue("The adjacent fuse must not own a terminal function", mapped[sample.devices.last()].isNullOrEmpty())
            val unproven = AbLayoutAnalysis.associateFunctions(sample.page, sample.devices, linkAreas = sample.fields.take(1))
            assertTrue("A lone property side cannot prove a terminal body", unproven.values.flatten().none { it.text == "FFU" })
        }
    }

    @Test fun multiplePinCaptionsKeepTheirBankWhenOnlyTheTwoOuterPropertyLinksSurvive() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val base = sample()
            val sample = base.copy(fields = listOf(base.fields.first(), base.fields.last())).rotate(rotation)
            val mapped = AbLayoutAnalysis.associateFunctions(sample.page, sample.devices, linkAreas = sample.fields)
            assertEquals("rotation=$rotation", listOf("公共电源-N24", "IO电源-P24", "ECT电源-P24"),
                mapped[sample.devices.first()].orEmpty().map { it.text })
            assertTrue(mapped[sample.devices.last()].isNullOrEmpty())
        }
    }

    @Test fun laterPinCaptionsKeepTheirMainDespiteACloserNextInstallationRowInAllDirections() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val sample = sample().rotate(rotation)
            val mapped = AbLayoutAnalysis.associateFunctions(sample.page, sample.devices, linkAreas = sample.fields)
            assertEquals("rotation=$rotation", listOf("公共电源-N24", "IO电源-P24", "ECT电源-P24"),
                mapped[sample.devices.first()].orEmpty().map { it.text })
            assertTrue(mapped[sample.devices.last()].isNullOrEmpty())
        }
    }

    @Test fun missingNativeIdentityModelOrPropertyBlockCannotExpandTheWideBank() {
        val sample = sample()
        val cases = listOf(
            sample.page.boxes.map { it.copy(sourceObject = -1, sourceRun = -1, sourceSequence = -1, sourceEndSequence = -1) },
            sample.page.boxes.map { if (it.text == "JWD2-2.5") it.copy(text = "(2A)") else it },
            sample.page.boxes.map { if (it.text == "11") it.copy(text = "1") else it },
            sample.page.boxes.map { if (it.text == "19") it.copy(baselineY = it.baselineY + 10f) else it },
        )
        for (boxes in cases) {
            val devices = boxes.filter { EplanParsing.deviceId(it.text) != null }
            val result = AbLayoutAnalysis.associateFunctions(sample.page.copy(boxes = boxes), devices, linkAreas = sample.fields)
            assertFalse(result[devices.first()].orEmpty().any { it.text == "ECT电源-P24" })
        }
        val result = AbLayoutAnalysis.associateFunctions(sample.page, sample.devices, linkAreas = emptyList())
        assertFalse(result[sample.devices.first()].orEmpty().any { it.text == "ECT电源-P24" })
    }

    @Test fun anotherActualMainEndsTheNativeBankBeforeItsLaterCaptions() {
        val sample = sample()
        val other = sample.devices.last().copy(sourceSequence = 35, sourceEndSequence = 38, sourceRun = 35)
        val boxes = sample.page.boxes.dropLast(1) + other
        val result = AbLayoutAnalysis.associateFunctions(sample.page.copy(boxes = boxes),
            listOf(sample.devices.first(), other), linkAreas = sample.fields)
        assertFalse(result[sample.devices.first()].orEmpty().any { it.text == "ECT电源-P24" })
    }

    private data class Sample(val page: PdfPageText, val devices: List<PdfTextBox>, val fields: List<PdfTextBox>) {
        fun rotate(turn: Int): Sample {
            fun box(b: PdfTextBox): PdfTextBox {
                val a = EplanParsing.rotate(b.x, b.y, page.width, page.height, turn)
                val z = EplanParsing.rotate(b.right, b.bottom, page.width, page.height, turn)
                val anchor = EplanParsing.rotate(b.baselineX, b.baselineY, page.width, page.height, turn)
                return b.copy(x = min(a.first, z.first), y = min(a.second, z.second),
                    width = abs(a.first-z.first), height = abs(a.second-z.second),
                    baselineX = anchor.first, baselineY = anchor.second, angle = (b.angle + turn) % 360)
            }
            return Sample(page.copy(boxes = page.boxes.map(::box)), devices.map(::box), fields.map(::box))
        }
    }

    private fun sample(): Sample {
        fun text(t: String, x: Float, y: Float, angle: Int, font: Float, sequence: Int, blue: Boolean = true) =
            PdfTextBox(t, x, y - 1f, 10f, 10f, x, y, angle, font, blue,
                sourceObject = sequence, sourceRun = sequence, sourceSequence = sequence, sourceEndSequence = sequence + 2)
        val main = PdfTextBox("-1XT90", 104f, 100f, 3f, 14f, 107f, 114f, 270, 3f, true,
            sourceObject = 1, sourceRun = 1, sourceSequence = 1, sourceEndSequence = 6)
        val neighbor = PdfTextBox("-1XT91", 158f, 160f, 3f, 14f, 161f, 174f, 270, 3f, true,
            sourceObject = 100, sourceRun = 100, sourceSequence = 100, sourceEndSequence = 106)
        val boxes = listOf(main,
            text("1", 101f, 160f, 0, .8f, 10), text("公共电源-N24", 100f, 162f, 45, 1.8f, 20),
            text("JWD2-2.5", 103f, 162f, 45, 1.8f, 30, false),
            text("11", 141f, 160f, 0, .8f, 40), text("IO电源-P24", 140f, 162f, 45, 1.8f, 50),
            text("19", 163f, 160f, 0, .8f, 60), text("ECT电源-P24", 162f, 162f, 45, 1.8f, 70), neighbor)
        val fields = listOf(PdfTextBox("", 98f, 100f, 6f, 80f), PdfTextBox("", 104f, 100f, 12f, 80f),
            PdfTextBox("", 116f, 100f, 6f, 80f))
        return Sample(PdfPageText(1, 500f, 500f, boxes), listOf(main, neighbor), fields)
    }
}
