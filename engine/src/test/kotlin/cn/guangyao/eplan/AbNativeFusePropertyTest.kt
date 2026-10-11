package cn.guangyao.eplan

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.min

class AbNativeFusePropertyTest {
    @get:org.junit.Rule val testDirectory = org.junit.rules.TemporaryFolder()
    private val cacheDirectory: java.io.File get() = testDirectory.root

    @Test fun originalDenseBankKeepsAllTwentyFuseFunctionsAndTheirOwnPrintedRatings() {
        val id = UUID.randomUUID().toString()
        val file = File(cacheDirectory, "native-fuses-$id.pdf")
        requireNotNull(javaClass.getResourceAsStream("/fixtures/b06-layouts-native.pdf")).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        try {
            val document = ImportedDocument(id, "Native fuse bank.pdf", file.absolutePath, file.length(), 0L)
            val result = OfflinePdfEngine(cacheDirectory, LayoutRasterSource { _, page, check ->
                check()
                javaClass.getResourceAsStream("/fixtures/b06-layouts-p$page.png")?.use { input ->
                    val image = requireNotNull(ImageIO.read(input))
                    AbInstallationOrder.Raster(image.width, image.height,
                        image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
                }
            }).extract(document, ExtractionMode.FUNCTION, RuleSettings(cabinetFilter = "C01"), {}, { false })
            // Independently transcribed from original B06/B07 page 130, not from engine output.
            val expected = listOf("(3A)接触器", "(6A)柜内照明", "(6A)机台照明",
                "(1A)风扇", "(1A)风扇", "(1A)风扇", "(1A)风扇", "(6A)安全", "(2A)IO", "(1A)ECT",
                "(3A)外设", "(4A)CN1", "(2A)抱闸", "(6A)STO", "(1A)风扇", "(1A)风扇",
                "(1A)风扇", "(1A)风扇", "(1A)C03风扇", "(1A)C03风扇")
            val actual = result.records.filter { it.kind == RecordKind.LABEL && it.layout == "C01" &&
                it.column == LabelColumn.FUNCTION && EplanParsing.deviceCategory(it.deviceId) == "FU" }
            assertEquals(expected.mapIndexed { index, caption -> "1FU${(index + 1).toString().padStart(2, '0')}" to caption },
                actual.map { it.deviceId to it.text })
            val functions = result.records.filter { it.kind == RecordKind.LABEL && it.column == LabelColumn.FUNCTION }
            assertTrue(functions.indexOf(actual.last()) < functions.indexOfFirst { it.deviceId == "1XT50" })
            assertTrue(functions.filter { it.deviceId.startsWith("1KA") }.none { it.text == "外设" || it.text == "C03风扇" })
            assertEquals(listOf("主塑壳"), functions.filter { it.deviceId == "1QS01" }.map { it.text })
            assertTrue(functions.indexOfFirst { it.deviceId == "1QS01" } < functions.indexOf(actual.first()))
            for (phase in 1..3) assertEquals("The native transformer block owns its own phase caption",
                listOf("L${phase}电流互感器"), functions.filter { it.deviceId == "1TA0$phase" }.map { it.text })
        } finally { file.delete() }
    }

    @Test fun overlappingFuseHitboxesUseIndependentNativeTripletsInEveryDirection() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val sample = sample().rotate(rotation)
            val mapped = AbLayoutAnalysis.associateFunctions(sample.page, sample.devices, linkAreas = sample.fields)
            assertEquals("rotation=$rotation", listOf("IO", "ECT"), sample.devices.map { mapped[it]?.single()?.text })
            assertEquals(listOf("(2A)IO", "(1A)ECT"), sample.devices.map { device ->
                LayoutFuseRatings.decorate(sample.page, EplanParsing.deviceId(device.text)!!, mapped.getValue(device).single()).text
            })
        }
    }

    @Test fun englishCaptionNeedsRatingNativeOrderOwnGeometryAndFuseCategory() {
        val sample = sample()
        fun assertRejected(boxes: List<PdfTextBox>, fields: List<PdfTextBox> = sample.fields,
                           devices: List<PdfTextBox> = sample.devices) {
            val mapped = AbLayoutAnalysis.associateFunctions(sample.page.copy(boxes = boxes), devices, linkAreas = fields)
            assertTrue(mapped.values.flatten().none { it.text == "IO" })
        }
        val amp = sample.page.boxes[1]
        val caption = sample.page.boxes[2]
        assertRejected(sample.page.boxes.filter { it != amp })
        assertRejected(sample.page.boxes.map { if (it == amp) it.copy(text = "MODEL") else it })
        assertRejected(sample.page.boxes.map { if (it == caption) it.copy(sourceSequence = 1, sourceEndSequence = 2) else it })
        assertRejected(sample.page.boxes.map { if (it == caption) it.copy(sourceObject = -1) else it })
        assertRejected(sample.page.boxes, emptyList())
        assertRejected(sample.page.boxes.map { if (it == caption) it.copy(x = 300f, baselineX = 300f) else it })
        val other = sample.devices.first().copy(text = "-1KM01")
        assertRejected(sample.page.boxes.map { if (it == sample.devices.first()) other else it },
            devices = listOf(other, sample.devices.last()))
    }

    @Test fun oppositeDirectionFuseCanRecoverItsMissingMiddleFromBothSideFields() {
        val original = sample().copy(fields = listOf(
            PdfTextBox("", 94f, 112f, 3f, 46f), PdfTextBox("", 103f, 112f, 3f, 46f)))
        for (rotation in listOf(0, 90, 180, 270)) {
            val sample = original.rotate(rotation)
            val result = AbLayoutAnalysis.associateFunctions(sample.page, sample.devices, linkAreas = sample.fields)
            assertEquals("rotation=$rotation", listOf("IO", "ECT"), sample.devices.map { result[it]?.single()?.text })
            val incomplete = AbLayoutAnalysis.associateFunctions(sample.page, sample.devices, linkAreas = sample.fields.take(1))
            assertTrue(incomplete.values.flatten().none { it.text == "IO" || it.text == "ECT" })
        }
    }

    @Test fun sameDirectionDenseFuseTripletsKeepOverlappingRatingsAndEnglishCaption() {
        val boxes = (0..1).flatMap { index ->
            val y = 150f + index * 3.8f
            val sequence = 10 + index * 20
            fun native(text: String, x: Float, run: Int, blue: Boolean, advance: Float) =
                PdfTextBox(text, x, y - 4f, advance, 4f, x, y, 0, 4f, blue, advance,
                    run, run, run, run)
            listOf(native("-1FU0${index + 1}", 149f, sequence, true, 18f),
                native(if (index == 0) "(2A)" else "(1A)", 206.4f, sequence + 1, false, 9f),
                native(if (index == 0) "IO电源" else "ECT", 201.6f, sequence + 2, true, 16f))
        }
        // Only equal head/tail cells survive; their double-width gap is the missing body.
        val fields = (0..1).flatMap { index ->
            listOf(PdfTextBox("", 149f, 145.2f + index * 3.8f, 18.4f, 5.6f),
                PdfTextBox("", 204.2f, 145.2f + index * 3.8f, 18.4f, 5.6f))
        }
        val original = Sample(PdfPageText(1, 500f, 500f, boxes), listOf(boxes[0], boxes[3]), fields)
        for (rotation in listOf(0, 90, 180, 270)) {
            val sample = original.rotate(rotation)
            val mapped = AbLayoutAnalysis.associateFunctions(sample.page, sample.devices, linkAreas = sample.fields)
            assertEquals("rotation=$rotation", listOf("(2A)IO电源", "(1A)ECT"), sample.devices.map { device ->
                LayoutFuseRatings.decorate(sample.page, EplanParsing.deviceId(device.text)!!,
                    mapped.getValue(device).single()).text
            })
            val withoutFields = AbLayoutAnalysis.associateFunctions(sample.page, sample.devices)
            assertTrue("English needs a proven property field", withoutFields.values.flatten().none { it.text == "ECT" })
        }
    }

    @Test fun tallSwitchCaptionStaysWithItsBodyBesideTheNextRailAndTransformerCaptionsUseTheirOwnBlocks() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val sample = tallSwitchAndTransformer().rotate(rotation)
            val mapped = AbLayoutAnalysis.associateFunctions(sample.page, sample.devices, linkAreas = sample.fields)
            assertEquals("rotation=$rotation", listOf("主开关"), mapped[sample.devices[0]].orEmpty().map { it.text })
            assertEquals("rotation=$rotation", listOf("L3互感器"), mapped[sample.devices[1]].orEmpty().map { it.text })
        }
    }

    @Test fun nativeModelTripletCannotPromoteAnEnglishCaptionWithoutTheCompleteOwnPropertyBlock() {
        val sample = tallSwitchAndTransformer()
        val caption = sample.page.boxes[1].copy(text = "Ready")
        val boxes = sample.page.boxes.toMutableList().apply { this[1] = caption }
        fun mapped(values: List<PdfTextBox> = boxes, fields: List<PdfTextBox> = sample.fields) =
            AbLayoutAnalysis.associateFunctions(sample.page.copy(boxes = values), sample.devices, linkAreas = fields)
        assertEquals(listOf("Ready"), mapped()[sample.devices[0]].orEmpty().map { it.text })
        // Two matching side cells prove the missing middle; a single side cannot.
        assertEquals(listOf("Ready"), mapped(fields = sample.fields.filterIndexed { i, _ -> i != 1 })
            .get(sample.devices[0]).orEmpty().map { it.text })
        assertTrue(mapped(fields = sample.fields.drop(2)).values.flatten().none { it.text == "Ready" })
        assertTrue(mapped(values = boxes.map { if (it == boxes[2]) it.copy(text = "315A") else it })
            .values.flatten().none { it.text == "Ready" })
        assertTrue(mapped(values = boxes.map { if (it == caption) it.copy(sourceSequence = 5, sourceEndSequence = 8) else it })
            .values.flatten().none { it.text == "Ready" })
        assertTrue(mapped(values = boxes.map { if (it == caption) it.copy(x = 300f, baselineX = 300f) else it })
            .values.flatten().none { it.text == "Ready" })
    }

    @Test fun nativeTripletRecoversTheDoubleWidthMiddleFromTwoSidesInEveryDirection() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val sample = tallSwitchAndTransformer().rotate(rotation)
            val sides = sample.fields.filterIndexed { i, _ -> i != 1 && i != 4 }
            val result = AbLayoutAnalysis.associateFunctions(sample.page, sample.devices, linkAreas = sides)
            assertEquals("rotation=$rotation", listOf("主开关"), result[sample.devices[0]].orEmpty().map { it.text })
            assertEquals("rotation=$rotation", listOf("L3互感器"), result[sample.devices[1]].orEmpty().map { it.text })
        }
    }

    private data class Sample(val page: PdfPageText, val devices: List<PdfTextBox>, val fields: List<PdfTextBox>) {
        fun rotate(rotation: Int): Sample {
            fun turn(box: PdfTextBox): PdfTextBox {
                val a = EplanParsing.rotate(box.x, box.y, page.width, page.height, rotation)
                val b = EplanParsing.rotate(box.right, box.bottom, page.width, page.height, rotation)
                val anchor = EplanParsing.rotate(box.baselineX, box.baselineY, page.width, page.height, rotation)
                return box.copy(x = min(a.first, b.first), y = min(a.second, b.second),
                    width = abs(a.first - b.first), height = abs(a.second - b.second),
                    baselineX = anchor.first, baselineY = anchor.second, angle = (box.angle + rotation) % 360)
            }
            return Sample(page.copy(boxes = page.boxes.map(::turn)), devices.map(::turn), fields.map(::turn))
        }
    }

    private fun sample(): Sample {
        val boxes = (0..1).flatMap { index ->
            val x = 100f + index * 1.921f
            val sequence = 10 + index * 20
            val run = 10 + index * 3
            listOf(
                PdfTextBox("-1FU0${index + 1}", x - 1.535f, 112.913f, 1.535f, 7.087f,
                    x, 120f, 270, 1.535f, true, 7.087f, run, run, sequence, sequence + 5),
                PdfTextBox(if (index == 0) "(2A)" else "(1A)", x - 1.696f, 144.691f, 1.535f, 4.724f,
                    x - 1.696f, 144.691f, 90, 1.535f, false, 4.724f, run + 1, run + 1, sequence + 6, sequence + 9),
                PdfTextBox(if (index == 0) "IO" else "ECT", x - 1.696f, 149.416f, 1.535f, 3.5f,
                    x - 1.696f, 149.416f, 90, 1.535f, true, 3.5f, run + 2, run + 2, sequence + 10, sequence + 12),
            )
        }
        val devices = listOf(boxes[0], boxes[3])
        val fields = listOf(PdfTextBox("", 95f, 112f, 12f, 46f), PdfTextBox("", 96.921f, 112f, 12f, 46f))
        return Sample(PdfPageText(1, 500f, 500f, boxes), devices, fields)
    }

    private fun tallSwitchAndTransformer(): Sample {
        val switch = PdfTextBox("-1QS09", 100f, 100f, 3f, 14f, 103f, 114f, 270, 3f, true,
            sourceObject = 10, sourceRun = 10, sourceSequence = 10, sourceEndSequence = 15)
        val switchCaption = PdfTextBox("主开关", 100f, 259f, 10f, 10f, 100f, 260f, 45, 1.8f, true,
            sourceObject = 11, sourceRun = 11, sourceSequence = 16, sourceEndSequence = 18)
        val switchModel = PdfTextBox("MX400", 108f, 259f, 10f, 10f, 108f, 260f, 45, 1.8f, false,
            sourceObject = 12, sourceRun = 12, sourceSequence = 20, sourceEndSequence = 28)
        val transformer = PdfTextBox("-1TA09", 100f, 240f, 3f, 14f, 103f, 254f, 270, 3f, true,
            sourceObject = 13, sourceRun = 13, sourceSequence = 30, sourceEndSequence = 35)
        val transformerModel = PdfTextBox("BH300", 93f, 268.2f, 10f, 1.8f, 93f, 270f, 0, 1.8f, false,
            sourceObject = 14, sourceRun = 14, sourceSequence = 36, sourceEndSequence = 44)
        val transformerCaption = PdfTextBox("L3互感器", 93f, 272.2f, 12f, 1.8f, 93f, 274f, 0, 1.8f, true,
            sourceObject = 15, sourceRun = 15, sourceSequence = 45, sourceEndSequence = 49)
        val boxes = listOf(switch, switchCaption, switchModel, transformer, transformerModel, transformerCaption)
        val fields = listOf(PdfTextBox("", 98f, 100f, 6f, 180f), PdfTextBox("", 104f, 100f, 12f, 180f),
            PdfTextBox("", 116f, 100f, 6f, 180f), PdfTextBox("", 90f, 240f, 9f, 35f),
            PdfTextBox("", 99f, 240f, 18f, 35f), PdfTextBox("", 117f, 240f, 9f, 35f))
        return Sample(PdfPageText(1, 600f, 600f, boxes), listOf(switch, transformer), fields)
    }
}
