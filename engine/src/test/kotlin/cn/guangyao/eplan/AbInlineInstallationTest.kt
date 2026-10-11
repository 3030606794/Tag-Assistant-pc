package cn.guangyao.eplan

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

class AbInlineInstallationTest {
    @Test fun nativeInlineBodyRecoversMissingLeaderInEveryPhysicalDirection() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val sample = sample().rotate(rotation)
            assertEquals("Only the opt-in A/B path recovers bare IDs", listOf(sample.marked),
                AbLayoutAnalysis.layoutDevices(sample.page, sample.fields))
            assertEquals("rotation=$rotation", setOf(sample.marked, sample.bare),
                AbLayoutAnalysis.layoutDevices(sample.page, sample.fields, recoverUnmarkedIds = true).toSet())
        }
    }

    @Test fun pageDisplayRotationKeepsRecoveredIdAndItsOwnNativeCaptionTogether() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val sample = sample().rotate(rotation, pageRotation = true)
            val devices = AbLayoutAnalysis.layoutDevices(sample.page, sample.fields, recoverUnmarkedIds = true)
            assertEquals(setOf(sample.marked, sample.bare), devices.toSet())
            val functions = AbLayoutAnalysis.associateFunctions(sample.page, devices, linkAreas = sample.fields)
            assertEquals("rotation=$rotation", listOf("接地端子"), functions[sample.bare].orEmpty().map { it.text })
        }
    }

    @Test fun inlineBodyNeedsBothNativePropertyRunsAndTheCompleteBlock() {
        val sample = sample()
        fun rejected(boxes: List<PdfTextBox> = sample.page.boxes, fields: List<PdfTextBox> = sample.fields) {
            assertEquals(listOf(sample.marked), AbLayoutAnalysis.layoutDevices(
                sample.page.copy(boxes = boxes), fields, recoverUnmarkedIds = true))
        }
        val caption = sample.page.boxes[1]
        val model = sample.page.boxes[2]
        rejected(fields = sample.fields.dropLast(1))
        rejected(fields = sample.fields.filterIndexed { i, _ -> i != 1 })
        rejected(fields = sample.fields.mapIndexed { i, field -> if (i == 1) field.copy(width = field.width * .7f) else field })
        rejected(boxes = sample.page.boxes.filter { it != model })
        rejected(boxes = sample.page.boxes.map { if (it == caption) it.copy(sourceObject = -1) else it })
        rejected(boxes = sample.page.boxes.map { if (it == model) it.copy(sourceRun = caption.sourceRun) else it })
        rejected(boxes = sample.page.boxes.map { if (it == model) it.copy(text = "说明文字") else it })
        rejected(boxes = sample.page.boxes.map { if (it == caption) it.copy(text = "UM77/78") else it })
        rejected(boxes = sample.page.boxes.map { if (it == caption) it.copy(baselineX = 330f, x = 330f) else it })
        rejected(boxes = sample.page.boxes.map { if (it == sample.bare) it.copy(blue = false) else it })
        // Same geometric block cannot turn a reference alongside an existing main into a new device.
        val existing = sample.marked.copy(x = 220f, y = 480f, baselineX = 220f, baselineY = 490f)
        assertEquals(listOf(existing), AbLayoutAnalysis.layoutDevices(
            sample.page.copy(boxes = sample.page.boxes.filter { it != sample.marked } + existing),
            sample.fields, recoverUnmarkedIds = true))
    }

    @Test fun inlineBodyAcceptsEitherNativeCaptionModelOrderAndRetainsRepeatedPhysicalIds() {
        val sample = sample()
        val caption = sample.page.boxes[1]
        val model = sample.page.boxes[2]
        val reordered = sample.page.boxes.map {
            when (it) {
                caption -> it.copy(sourceRun = model.sourceRun, sourceSequence = model.sourceSequence,
                    sourceEndSequence = model.sourceEndSequence)
                model -> it.copy(sourceRun = caption.sourceRun, sourceSequence = caption.sourceSequence,
                    sourceEndSequence = caption.sourceEndSequence)
                else -> it
            }
        }.reversed()
        assertEquals(setOf(sample.marked, sample.bare), AbLayoutAnalysis.layoutDevices(
            sample.page.copy(boxes = reordered), sample.fields.reversed(), recoverUnmarkedIds = true).toSet())
        val duplicate = sample.page.boxes.take(3).map { it.copy(y = it.y + 110f,
            baselineY = it.baselineY + 110f, sourceObject = it.sourceObject + 20,
            sourceRun = it.sourceRun + 20, sourceSequence = it.sourceSequence + 200,
            sourceEndSequence = it.sourceEndSequence + 200) }
        val result = AbLayoutAnalysis.layoutDevices(sample.page.copy(boxes = sample.page.boxes + duplicate),
            sample.fields + sample.fields.map { it.copy(y = it.y + 110f) }, recoverUnmarkedIds = true)
        assertEquals(listOf(sample.bare, duplicate.first()), result.filter { it.text == sample.bare.text })
    }

    @Test fun nativeElongatedCombinedDeviceOwnsItsCaptionEvenWhenTheFieldReachesTheNextRail() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val sample = elongatedSample().rotate(rotation)
            val devices = AbLayoutAnalysis.layoutDevices(sample.page, sample.fields, recoverUnmarkedIds = true)
            assertEquals("rotation=$rotation", setOf(sample.bare, sample.marked), devices.toSet())
            val functions = AbLayoutAnalysis.associateFunctions(sample.page, devices, linkAreas = sample.fields)
            assertEquals("Caption stays with combined installation, not the lower rail", listOf(sample.page.boxes[1].text),
                functions[sample.bare].orEmpty().map { it.text })
            assertEquals(emptyList<String>(), functions[sample.marked].orEmpty().map { it.text })
        }
    }

    @Test fun elongatedBodyExceptionNeedsNativeTripletFullBlockAndUnoccupiedHead() {
        val sample = elongatedSample()
        val missingTail = AbLayoutAnalysis.layoutDevices(sample.page, sample.fields.dropLast(1), recoverUnmarkedIds = true)
        assertEquals(listOf(sample.marked), missingTail)
        val missingNativeModel = sample.page.copy(boxes = sample.page.boxes.mapIndexed { i, box ->
            if (i == 2) box.copy(sourceObject = -1) else box
        })
        assertEquals(listOf(sample.marked), AbLayoutAnalysis.layoutDevices(
            missingNativeModel, sample.fields, recoverUnmarkedIds = true))
        val occupied = sample.marked.copy(x = sample.bare.x, y = sample.bare.y,
            baselineX = sample.bare.baselineX, baselineY = sample.bare.baselineY)
        val occupiedPage = sample.page.copy(boxes = sample.page.boxes.dropLast(1) + occupied)
        assertEquals(listOf(occupied), AbLayoutAnalysis.layoutDevices(occupiedPage, sample.fields, recoverUnmarkedIds = true))
    }

    private data class Sample(val page: PdfPageText, val fields: List<PdfTextBox>) {
        val bare get() = page.boxes.first()
        val marked get() = page.boxes.last()
        fun rotate(angle: Int, pageRotation: Boolean = false): Sample {
            fun turn(box: PdfTextBox): PdfTextBox {
                val a = EplanParsing.rotate(box.x, box.y, page.width, page.height, angle)
                val b = EplanParsing.rotate(box.right, box.bottom, page.width, page.height, angle)
                val anchor = EplanParsing.rotate(box.baselineX, box.baselineY, page.width, page.height, angle)
                return box.copy(x = min(a.first, b.first), y = min(a.second, b.second),
                    width = abs(a.first - b.first), height = abs(a.second - b.second),
                    baselineX = anchor.first, baselineY = anchor.second, angle = (box.angle + angle) % 360)
            }
            return Sample(page.copy(width = if (angle % 180 == 0) page.width else page.height,
                height = if (angle % 180 == 0) page.height else page.width,
                boxes = page.boxes.map(::turn), rotation = if (pageRotation) angle else 0), fields.map(::turn))
        }
    }

    private fun sample(): Sample {
        // Native B26 C01 geometry, with deliberately different device identifiers. The ID,
        // hardware and description occupy three cells, not one tall shared hit rectangle.
        val bare = PdfTextBox("9XT73", 189.274f, 482.248f, 21.260f, 8.504f,
            189.274f, 490.752f, 0, 8.504f, true, sourceObject = 10, sourceRun = 10,
            sourceSequence = 100, sourceEndSequence = 104)
        val caption = PdfTextBox("接地端子", 265.527f, 476.669f, 18.040f, 18.040f,
            268.626f, 494.200f, 315, 5.102f, true, sourceObject = 11, sourceRun = 11,
            sourceSequence = 105, sourceEndSequence = 108)
        val model = caption.copy(text = "JDG-C-13", y = 469.866f, baselineY = 487.397f,
            blue = false, sourceObject = 12, sourceRun = 12, sourceSequence = 109, sourceEndSequence = 116)
        val marked = bare.copy(text = "-9QF73", x = 600f, baselineX = 600f,
            sourceObject = 30, sourceRun = 30, sourceSequence = 300, sourceEndSequence = 305)
        val fields = listOf(area(189.274f, 469.866f, 212.975f, 495.218f),
            area(212.975f, 469.866f, 260.375f, 495.218f),
            area(260.375f, 469.866f, 284.075f, 495.218f))
        return Sample(PdfPageText(7, 1202f, 853f, listOf(bare, caption, model, marked)), fields)
    }

    private fun elongatedSample(): Sample {
        // C05's combined servo has a long native diagonal description. The field exceeds 35%
        // of page height and overlaps the next rail, so proximity and broad-box uniqueness fail.
        val bare = PdfTextBox("UM80/83", 827.404f, 215.876f, 6.909f, 37.204f,
            834.313f, 253.080f, 270, 6.909f, true, 37.204f,
            sourceObject = 10, sourceRun = 10, sourceSequence = 100, sourceEndSequence = 106)
        val caption = PdfTextBox("焊接2A_Z轴（伺服电机+抱闸）/焊接2B_Z轴（伺服电机+抱闸）",
            819.439f, 390.511f, 130f, 130f, 822.371f, 393.443f, 45, 4.146f, true,
            sourceObject = 11, sourceRun = 11, sourceSequence = 107, sourceEndSequence = 149)
        val model = caption.copy(text = "SV630NDS5R5I-FS", x = 827.943f, width = 40f, height = 40f,
            baselineX = 830.875f, blue = false, sourceObject = 12, sourceRun = 12,
            sourceSequence = 150, sourceEndSequence = 164)
        val lowerRail = bare.copy(text = "-9XT54", x = 826.821f, y = 451.788f,
            baselineX = 833.730f, baselineY = 483.678f, height = 31.890f,
            sourceObject = 30, sourceRun = 30, sourceSequence = 300, sourceEndSequence = 305)
        val fields = listOf(area(821.099f, 215.876f, 853.391f, 518.737f),
            area(853.391f, 215.876f, 917.975f, 518.737f), area(917.975f, 215.876f, 950.267f, 518.737f))
        return Sample(PdfPageText(7, 1202f, 853f, listOf(bare, caption, model, lowerRail)), fields)
    }

    private fun area(left: Float, top: Float, right: Float, bottom: Float) =
        PdfTextBox("", left, top, right - left, bottom - top)
}
