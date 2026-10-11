package cn.guangyao.eplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AbInstallationOrderTest {
    @Test fun installationBandsKeepSideCompartmentAndTallBodyInTheirPhysicalRows() {
        val sample = cabinet()
        val result = AbInstallationOrder.order(sample.page, sample.page.boxes, sample.raster)
        assertTrue(result.confident)
        assertEquals(sample.expected, result.positions.map { it.box.text })
        assertTrue(result.positions.all { it.rotation == 0 })
        // Geometry assists ordering only: original source objects, coordinates and multiplicity survive.
        result.positions.forEach { position -> assertTrue(sample.page.boxes.any { it === position.box }) }
    }

    @Test fun theSameCabinetIsRecognizedInAllFourDisplayedPageDirections() {
        val original = cabinet()
        for (turn in listOf(0, 90, 180, 270)) {
            val sample = turned(original, turn)
            val result = AbInstallationOrder.order(sample.page, sample.page.boxes, sample.raster)
            assertTrue("Raster turn $turn", result.confident)
            assertEquals("Raster turn $turn", original.expected, result.positions.map { it.box.text })
            assertTrue(result.positions.all { it.rotation == (360 - turn) % 360 })
        }
    }

    @Test fun nativePageUnitsDoNotDependOnThumbnailPixelResolution() {
        val sample = cabinet()
        val half = AbInstallationOrder.Raster(sample.raster.width / 2, sample.raster.height / 2,
            IntArray(sample.raster.width / 2 * (sample.raster.height / 2)) { index ->
                val x = index % (sample.raster.width / 2); val y = index / (sample.raster.width / 2)
                sample.raster.pixels[y * 2 * sample.raster.width + x * 2]
            })
        val result = AbInstallationOrder.order(sample.page, sample.page.boxes, half)
        assertTrue(result.confident)
        assertEquals(sample.expected, result.positions.map { it.box.text })
    }

    @Test fun missingOrGrayRasterDoesNotRearrangeTextWithoutAlignedEdgeEvidence() {
        val sample = cabinet()
        val old = AbLayoutAnalysis.installedPositions(sample.page, sample.page.boxes)
        val gray = sample.raster.copy(pixels = sample.raster.pixels.map { pixel ->
            val value = ((pixel ushr 16 and 255) + (pixel ushr 8 and 255) + (pixel and 255)) / 3
            (0xff000000L or (value.toLong() shl 16) or (value.toLong() shl 8) or value.toLong()).toInt()
        }.toIntArray())
        for (raster in listOf(null, gray)) {
            val result = AbInstallationOrder.order(sample.page, sample.page.boxes, raster)
            assertFalse(result.confident)
            assertEquals(old, result.positions)
        }
    }

    @Test fun fullyCoveredOrangeRailsStillUseTheColoredFrameAndDuctBandsInEveryDirection() {
        val sample = cabinet()
        val covered = sample.copy(raster = sample.raster.copy(
            pixels = sample.raster.pixels.map { if (it == ORANGE) GRAY else it }.toIntArray(),
        ))
        for (turn in listOf(0, 90, 180, 270)) {
            val displayed = turned(covered, turn)
            val result = AbInstallationOrder.order(displayed.page, displayed.page.boxes, displayed.raster)
            assertTrue("Covered rails, turn $turn", result.confident)
            assertEquals("Covered rails, turn $turn", sample.expected, result.positions.map { it.box.text })
            assertTrue(result.positions.all { it.rotation == (360 - turn) % 360 })
        }
    }

    @Test fun unbrokenSidePocketsUseTheStartingBodyBandInsteadOfTheEntirePocketCenter() {
        val sample = cabinet()
        val pixels = sample.raster.pixels.copyOf()
        fun fill(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
            for (y in top..bottom) for (x in left..right) pixels[y * sample.raster.width + x] = color
        }
        // One unbroken side compartment: upper busbar, tall breaker, then lower instruments.
        fill(90, 120, 239, 530, GRAY)
        // A second pocket spans the last two rows. Tall drives begin inside the adjacent duct
        // gap and continue into the bottom band; their raised vertical labels are in row 5.
        fill(90, 570, 239, 765, GRAY)
        fill(110, 660, 150, 765, BLACK)
        fill(170, 660, 210, 765, BLACK)
        val boxes = sample.page.boxes.map { box ->
            if (box.text == "-7XT01") box.copy(y = 150f, baselineY = 159f) else box
        } + listOf(
            PdfTextBox("-7UM92", 127f, 626f, 6f, 24f, 130f, 650f, angle = 270, fontSize = 6f, blue = true),
            PdfTextBox("-7UM93", 187f, 626f, 6f, 24f, 190f, 650f, angle = 270, fontSize = 6f, blue = true),
        )
        val expected = sample.expected.dropLast(1) + listOf("-7UM92", "-7UM93", "-7FU01")
        val original = sample.copy(page = sample.page.copy(boxes = boxes),
            raster = sample.raster.copy(pixels = pixels), expected = expected)
        for (turn in listOf(0, 90, 180, 270)) {
            val displayed = turned(original, turn)
            val result = AbInstallationOrder.order(displayed.page, displayed.page.boxes, displayed.raster)
            assertTrue("Side pocket, turn $turn", result.confident)
            assertEquals("Side pocket, turn $turn", expected, result.positions.map { it.box.text })
        }
    }

    @Test fun commonBodyFacingTextEdgesKeepLongVerticalIdentifiersInsideTheirOwnRowWithoutPixels() {
        fun label(id: String, x: Float, top: Float, height: Float) =
            PdfTextBox(id, x, top, 10f, height, x + 8f, top + height,
                angle = 270, fontSize = 10f, blue = true, advance = height)
        val boxes = listOf(
            label("-7R07/08", 70f, 150f, 40f), label("-7R06", 150f, 150f, 20f),
            label("-7R02/03", 230f, 150f, 40f), label("-7R01", 310f, 150f, 20f),
            label("-7R10/11", 70f, 300f, 40f), label("-7R09", 150f, 300f, 20f),
            label("-7R14/15", 230f, 300f, 40f), label("-7R13", 310f, 300f, 20f),
        )
        val page = PdfPageText(1, 400f, 450f, boxes)
        val sample = Sample(page, AbInstallationOrder.Raster(400, 450, IntArray(400 * 450) { WHITE }), boxes.map { it.text })
        for (turn in listOf(0, 90, 180, 270)) {
            val displayed = turned(sample, turn)
            val result = AbInstallationOrder.order(displayed.page, displayed.page.boxes, null, (360 - turn) % 360)
            assertFalse("Text evidence cannot establish a new cabinet orientation", result.confident)
            assertEquals("Vertical row, turn $turn", sample.expected, result.positions.map { it.box.text })
            result.positions.forEach { position -> assertTrue(displayed.page.boxes.any { it === position.box }) }
        }
    }

    @Test fun invalidRasterGeometryFallsBackWithoutChangingAnExplicitRotation() {
        val sample = cabinet()
        val old = AbLayoutAnalysis.installedPositions(sample.page, sample.page.boxes, 90)
        for (raster in listOf(
            AbInstallationOrder.Raster(0, 0, intArrayOf()),
            AbInstallationOrder.Raster(600, 900, IntArray(3)),
            AbInstallationOrder.Raster(100, 100, IntArray(10000)),
        )) {
            val result = AbInstallationOrder.order(sample.page, sample.page.boxes, raster, 90)
            assertFalse(result.confident)
            assertEquals(old, result.positions)
        }
    }

    @Test fun metadataPageRotationCannotReverseFallbackRowsWhenArtworkIsAbsentOrUnrecognized() {
        val original = fallbackRows()
        for (turn in listOf(0, 90, 180, 270)) {
            val displayed = turned(original, turn).let { it.copy(page = it.page.copy(rotation = turn)) }
            for (raster in listOf(null, displayed.raster)) {
                val result = AbInstallationOrder.order(displayed.page, displayed.page.boxes, raster)
                assertFalse("No cabinet direction was inferred from the blank artwork", result.confident)
                assertEquals("PDF /Rotate=$turn preserves source row order", original.expected,
                    result.positions.map { it.box.text })
                assertTrue(result.positions.all { it.rotation == (360 - turn) % 360 })
                result.positions.forEach { position -> assertTrue(displayed.page.boxes.any { it === position.box }) }
            }
        }
    }

    @Test fun explicitFallbackTurnRemainsRelativeToTheDisplayedPage() {
        val original = fallbackRows()
        for (turn in listOf(90, 180, 270)) {
            val displayed = turned(original, turn).let { it.copy(page = it.page.copy(rotation = turn)) }
            for (override in listOf(0, 90, 180, 270)) {
                val reference = AbInstallationOrder.order(original.page, original.page.boxes, null, (override + turn) % 360)
                val result = AbInstallationOrder.order(displayed.page, displayed.page.boxes, null, override)
                assertFalse(result.confident)
                assertEquals("PDF /Rotate=$turn, explicit=$override", reference.positions.map { it.box.text },
                    result.positions.map { it.box.text })
                assertTrue(result.positions.all { it.rotation == override })
            }
        }
    }

    @Test fun explicitDirectionTakesPriorityOverTheColoredFrameConvention() {
        val sample = cabinet()
        val result = AbInstallationOrder.order(sample.page, sample.page.boxes, sample.raster, 180)
        assertTrue(result.confident)
        assertTrue(result.positions.all { it.rotation == 180 })
        assertEquals(sample.page.boxes.toSet(), result.positions.map { it.box }.toSet())
    }

    @Test fun twoComparableCabinetFramesDoNotCreateOnePageWideInstallationPlan() {
        val sample = cabinet()
        val width = sample.raster.width * 2
        val pixels = IntArray(width * sample.raster.height) { index ->
            val x = index % width; val y = index / width
            sample.raster.pixels[y * sample.raster.width + x % sample.raster.width]
        }
        val other = sample.page.boxes.map { it.copy(x = it.x + sample.page.width, baselineX = it.baselineX + sample.page.width) }
        val page = sample.page.copy(width = sample.page.width * 2, boxes = sample.page.boxes + other)
        val expected = AbLayoutAnalysis.installedPositions(page, page.boxes)
        val result = AbInstallationOrder.order(page, page.boxes, AbInstallationOrder.Raster(width, sample.raster.height, pixels))
        assertFalse(result.confident)
        assertEquals(expected, result.positions)
    }

    @Test fun cancellationDuringPixelAnalysisEscapesWithoutReturningAPartialOrder() {
        val sample = cabinet()
        val cancelled = IllegalStateException("cancelled")
        var checks = 0
        val thrown = assertThrows(IllegalStateException::class.java) {
            AbInstallationOrder.order(sample.page, sample.page.boxes, sample.raster,
                checkCancelled = { if (++checks == 80) throw cancelled })
        }
        assertSame(cancelled, thrown)
    }

    @Test fun incompleteDuctColorsUsePhysicalAxisLengthsAndNativePropertiesInEveryDirection() {
        for (twoColors in listOf(false, true)) {
            val original = corroboratedCabinet(twoColors)
            for (turn in listOf(0, 90, 180, 270)) {
                val sample = turned(original, turn)
                val devices = sample.page.boxes.filter { it.text.startsWith("-7") }
                val result = AbInstallationOrder.order(sample.page.copy(rotation = turn), devices, sample.raster)
                assertTrue("Missing colors=$twoColors, PDF turn=$turn", result.confident)
                assertEquals(original.expected, result.positions.map { it.box.text })
                assertTrue(result.positions.all { it.rotation == (360 - turn) % 360 })
            }
        }
    }

    @Test fun conflictingDimensionsOrPropertiesCannotTurnAnIncompleteColorCabinet() {
        val original = corroboratedCabinet()
        for (changeDimensions in listOf(false, true)) {
            val boxes = original.page.boxes.map { box ->
                when {
                    changeDimensions && box.text.endsWith(" mm") -> box.copy(angle = 180)
                    !changeDimensions && box.text.startsWith("设备功能") -> box.copy(
                        y = box.y - 90, baselineY = box.baselineY - 90)
                    else -> box
                }
            }
            val page = original.page.copy(boxes = boxes)
            val result = AbInstallationOrder.order(page, boxes.filter { it.text.startsWith("-7") }, original.raster)
            assertFalse("Independent up/down evidence disagrees", result.confident)
        }
    }

    @Test fun productNumbersAndRepeatedLengthsAreNotDuctOrientationEvidence() {
        val original = corroboratedCabinet()
        for (kind in 0..3) {
            val boxes = original.page.boxes.map { box ->
                if (!box.text.endsWith(" mm")) box else when (kind) {
                    0 -> box.copy(text = "WPD_104_1x25+1x16")
                    1 -> box.copy(blue = false)
                    2 -> box.copy(x = 290f, baselineX = 290f) // Neutral component pocket, not duct.
                    else -> box.copy(y = 160f, baselineY = 165f) // One repeated physical dimension.
                }
            }
            val page = original.page.copy(boxes = boxes)
            assertFalse("Invalid length evidence $kind", AbInstallationOrder.order(page,
                boxes.filter { it.text.startsWith("-7") }, original.raster).confident)
        }
    }

    @Test fun repeatedEdgesOfOneRailDoNotEstablishSeveralInstalledRows() {
        val sample = corroboratedCabinet()
        val pixels = sample.raster.pixels.copyOf()
        for (y in 0 until sample.raster.height) for (x in 0 until sample.raster.width) {
            val index = y * sample.raster.width + x
            if (pixels[index] == ORANGE && y !in 150..180) pixels[index] = GRAY
        }
        val result = AbInstallationOrder.order(sample.page, sample.page.boxes.filter { it.text.startsWith("-7") },
            sample.raster.copy(pixels = pixels))
        assertFalse(result.confident)
    }

    @Test fun noNativePropertyProvenanceMeansNoSupplementaryCabinetTurn() {
        val sample = corroboratedCabinet()
        val page = sample.page.copy(boxes = sample.page.boxes.map { it.copy(sourceObject = -1, sourceRun = -1,
            sourceSequence = -1, sourceEndSequence = -1) })
        assertFalse(AbInstallationOrder.order(page, page.boxes.filter { it.text.startsWith("-7") }, sample.raster).confident)
    }

    @Test fun explicitRotationOverridesSupplementaryDirectionEvidence() {
        val sample = corroboratedCabinet()
        val result = AbInstallationOrder.order(sample.page, sample.page.boxes.filter { it.text.startsWith("-7") },
            sample.raster, 180)
        assertTrue(result.confident)
        assertTrue(result.positions.all { it.rotation == 180 })
    }

    @Test fun shortTopDuctDoesNotMergeTheFirstPhysicalRowIntoTheSecond() {
        val sample = nativeRows()
        val pixels = sample.raster.pixels.copyOf()
        // The top duct covers only one third of the width. Side/bottom connected framing
        // and the lower full-width separators remain, as do the actual first-row bodies.
        for (y in 61..100) for (x in 220..509) pixels[y * sample.raster.width + x] = GRAY
        // Row 2's long labels protrude into the preceding duct. Their body is below it;
        // recovering the missing first band must not absorb these raised identifiers.
        val raised = sample.page.boxes.mapIndexed { index, box ->
            if (index in 4..7) box.copy(y = box.y - 17f, baselineY = box.baselineY - 17f) else box
        }
        for (turn in listOf(0, 90, 180, 270)) {
            val displayed = turned(sample.copy(page = sample.page.copy(boxes = raised),
                raster = sample.raster.copy(pixels = pixels)), turn)
            val result = AbInstallationOrder.order(displayed.page, displayed.page.boxes, displayed.raster)
            assertTrue(result.confident)
            assertEquals(sample.expected, result.positions.map { it.box.text })
        }
    }

    @Test fun sharedBroadCompartmentKeepsEachBodyRowContiguousWithMixedIdentifierLengths() {
        val sample = nativeRows()
        val pixels = sample.raster.pixels.copyOf()
        // Remove a separator without changing the three-color orientation evidence.
        for (y in 231..259) for (x in 91..509) pixels[y * sample.raster.width + x] = GRAY
        // The first ID is beside its body, below the other IDs above that same row. Body
        // alignment, rather than the horizontal label's bottom edge, keeps it first.
        val boxes = sample.page.boxes.mapIndexed { index, box ->
            if (index == 0) box.copy(x = 108f, y = 140f, width = 20f, height = 6f,
                baselineX = 108f, baselineY = 146f, angle = 0) else box
        }
        for (turn in listOf(0, 90, 180, 270)) {
            val displayed = turned(sample.copy(page = sample.page.copy(boxes = boxes),
                raster = sample.raster.copy(pixels = pixels)), turn)
            val result = AbInstallationOrder.order(displayed.page, displayed.page.boxes, displayed.raster)
            assertTrue(result.confident)
            assertEquals(sample.expected, result.positions.map { it.box.text })
        }
        val noBodies = pixels.map { if (it == BLACK) GRAY else it }.toIntArray()
        val unproven = AbInstallationOrder.order(sample.page, sample.page.boxes, sample.raster.copy(pixels = noBodies))
        assertTrue(unproven.confident)
        assertFalse("Aligned text alone must not split an established physical band",
            unproven.positions.map { it.box.text } == sample.expected)
    }

    @Test fun nearbyAccessoryHeadsStayWithTheirBodyLayerWithoutVetoingTheNextInstalledRow() {
        val sample = nativeRows()
        val pixels = sample.raster.pixels.copyOf()
        for (y in 231..259) for (x in 91..509) pixels[y * sample.raster.width + x] = GRAY
        val extra = sample.page.boxes.take(4).mapIndexed { index, box ->
            val x = box.cx.toInt()
            for (y in 108..115) for (column in x - 20..x + 20) pixels[y * sample.raster.width + column] = BLACK
            box.copy(text = "-7FB${index + 1}", y = 82f, height = 20f, baselineY = 102f,
                sourceObject = 100 + index, sourceRun = 100 + index,
                sourceSequence = 1000 + index * 10, sourceEndSequence = 1008 + index * 10)
        }
        val expected = sample.expected.take(4).flatMapIndexed { index, id -> listOf(extra[index].text, id) } +
            sample.expected.drop(4)
        val original = sample.copy(page = sample.page.copy(boxes = sample.page.boxes + extra),
            raster = sample.raster.copy(pixels = pixels))
        for (turn in listOf(0, 90, 180, 270)) {
            val displayed = turned(original, turn)
            val result = AbInstallationOrder.order(displayed.page, displayed.page.boxes, displayed.raster)
            assertTrue(result.confident)
            assertEquals(expected, result.positions.map { it.box.text })
        }
    }

    @Test fun closedTopPocketUsesIndependentBelowAndSideBodiesWithoutWeakeningOrdinaryBodyThreshold() {
        val sample = nativeRows()
        val pixels = sample.raster.pixels.copyOf()
        for (y in 61..100) for (x in 91..509) pixels[y * sample.raster.width + x] = GRAY
        // The first horizontal label crosses a vertical duct. The adjacent body, rather
        // than the label center on colored artwork, identifies its shared compartment.
        for (y in 61..230) for (x in 115..121) pixels[y * sample.raster.width + x] = CYAN
        // Two IDs lie beside their bodies, one lies above a body, and an instrument ID
        // has no broad edge immediately below it. All four share one closed top pocket.
        val boxes = sample.page.boxes.mapIndexed { index, box ->
            if (index < 2) box.copy(x = box.cx - 42f, y = 140f, width = 20f, height = 6f,
                baselineX = box.cx - 42f, baselineY = 146f, angle = 0) else box
        }
        for (y in 130..191) for (x in 400..440) pixels[y * sample.raster.width + x] = GRAY
        val original = sample.copy(page = sample.page.copy(boxes = boxes), raster = sample.raster.copy(pixels = pixels))
        for (turn in listOf(0, 90, 180, 270)) {
            val displayed = turned(original, turn)
            val result = AbInstallationOrder.order(displayed.page, displayed.page.boxes, displayed.raster)
            assertTrue(result.confident)
            assertEquals("Shared pocket turn=$turn", sample.expected, result.positions.map { it.box.text })
        }
        // The same text/pocket with only one actual body is insufficient evidence.
        val empty = pixels.copyOf()
        for (y in 130..191) for (x in 130..170) empty[y * sample.raster.width + x] = GRAY
        for (y in 130..191) for (x in 220..260) empty[y * sample.raster.width + x] = GRAY
        val rejected = AbInstallationOrder.order(original.page, boxes, sample.raster.copy(pixels = empty))
        assertFalse(rejected.positions.map { it.box.text } == sample.expected)
    }

    @Test fun lowerSideBodiesFollowTheUpperRailEvenWhenTheirLabelsAreCloserToThatRail() {
        val sample = nativeRows()
        val pixels = sample.raster.pixels.copyOf()
        // A narrow side pocket continues through the separator. Two installed bodies
        // begin after the first main row, although their raised IDs are nearer that row.
        for (y in 101..389) for (x in 91..127) pixels[y * sample.raster.width + x] = GRAY
        for (y in 241..257) for (x in 95..105) pixels[y * sample.raster.width + x] = BLACK
        for (y in 241..257) for (x in 111..121) pixels[y * sample.raster.width + x] = BLACK
        val extra = listOf(100f, 116f).mapIndexed { index, x ->
            PdfTextBox("-7TA${index + 1}", x - 2, 222f, 4f, 14f, x, 222f,
                angle = 90, fontSize = 4f, blue = true, sourceObject = 100 + index,
                sourceRun = 100 + index, sourceSequence = 1000 + index * 10,
                sourceEndSequence = 1008 + index * 10)
        }
        val expected = sample.expected.take(4) + extra.map { it.text } + sample.expected.drop(4)
        val original = corroboratedCabinet(sample = sample.copy(page = sample.page.copy(boxes = sample.page.boxes + extra),
            raster = sample.raster.copy(pixels = pixels)))
        for (turn in listOf(0, 90, 180, 270)) {
            val displayed = turned(original, turn)
            val result = AbInstallationOrder.order(displayed.page, displayed.page.boxes.filter { it.text.startsWith("-7") }, displayed.raster)
            assertTrue(result.confident)
            assertEquals("Lower side pocket turn=$turn", expected, result.positions.map { it.box.text })
        }
    }

    private data class Sample(val page: PdfPageText, val raster: AbInstallationOrder.Raster, val expected: List<String>)

    private fun corroboratedCabinet(twoColors: Boolean = false, sample: Sample = cabinet()): Sample {
        val boxes = mutableListOf<PdfTextBox>()
        sample.page.boxes.forEachIndexed { index, original ->
            val sequence = index * 100
            val device = original.copy(sourceObject = index * 3, sourceRun = index * 3,
                sourceSequence = sequence, sourceEndSequence = sequence + 8)
            boxes += device
            boxes += PdfTextBox("设备功能$index", device.baselineX + 5, device.baselineY + 40, 15f, 5f,
                device.baselineX + 5, device.baselineY + 45, angle = 45, fontSize = 3f, blue = true,
                sourceObject = index * 3 + 1, sourceRun = index * 3 + 1,
                sourceSequence = sequence + 10, sourceEndSequence = sequence + 18)
            boxes += PdfTextBox("TYPE123", device.baselineX + 10, device.baselineY + 40, 15f, 5f,
                device.baselineX + 10, device.baselineY + 45, angle = 45, fontSize = 3f, blue = false,
                sourceObject = index * 3 + 2, sourceRun = index * 3 + 2,
                sourceSequence = sequence + 20, sourceEndSequence = sequence + 28)
        }
        listOf(160f, 340f, 480f, 700f).forEach { y ->
            boxes += PdfTextBox("450 mm", 65f, y, 12f, 5f, 65f, y + 5f,
                angle = 0, fontSize = 3f, blue = true)
        }
        val pixels = sample.raster.pixels.map { color -> when {
            color == PURPLE -> CYAN
            color == PINK && !twoColors -> CYAN
            else -> color
        } }.toIntArray()
        return sample.copy(page = sample.page.copy(boxes = boxes), raster = sample.raster.copy(pixels = pixels))
    }

    private fun nativeRows(): Sample {
        val width = 600; val height = 900
        val pixels = IntArray(width * height) { WHITE }
        fun fill(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
            for (y in top..bottom) for (x in left..right) pixels[y * width + x] = color
        }
        fill(60, 60, 540, 820, PINK)
        fill(510, 100, 540, 779, PURPLE)
        fill(60, 780, 540, 820, CYAN)
        val boxes = mutableListOf<PdfTextBox>()
        for ((row, top) in listOf(101, 260, 450, 630).withIndex()) {
            fill(90, top, 509, top + 129, GRAY)
            fill(110, top + 70, 490, top + 74, ORANGE)
            for (column in 0..3) {
                val x = 150f + column * 90
                val bottom = top + 25f
                val length = if (column % 2 == 0) 20f else 32f
                val id = "-7QF${row * 4 + column + 1}"
                boxes += PdfTextBox(id, x - 3, bottom - length, 6f, length, x, bottom,
                    angle = 270, fontSize = 6f, blue = true, sourceObject = row * 4 + column,
                    sourceRun = row * 4 + column, sourceSequence = row * 100 + column * 10,
                    sourceEndSequence = row * 100 + column * 10 + 8)
                fill(x.toInt() - 20, bottom.toInt() + 4, x.toInt() + 20, top + 90, BLACK)
            }
        }
        return Sample(PdfPageText(1, width.toFloat(), height.toFloat(), boxes),
            AbInstallationOrder.Raster(width, height, pixels), boxes.map { it.text })
    }

    private fun fallbackRows(): Sample {
        val names = listOf("-7FU08", "-7FU06", "-7FU02", "-7QF11", "-7QF13", "-7QF19")
        val boxes = names.mapIndexed { i, name ->
            val x = 80f + (i % 3) * 90f
            val baseline = if (i < 3) 140f else 300f
            PdfTextBox(name, x - 5f, baseline - 25f, 5f, 25f, x, baseline,
                angle = 270, fontSize = 5f, blue = true, advance = 25f)
        }
        return Sample(PdfPageText(3, 400f, 450f, boxes),
            AbInstallationOrder.Raster(400, 450, IntArray(400 * 450) { WHITE }), names)
    }

    /** A schematic cabinet, not an image copy of any user's drawing. */
    private fun cabinet(): Sample {
        val width = 600; val height = 900
        val pixels = IntArray(width * height) { WHITE }
        fun fill(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
            for (y in top..bottom) for (x in left..right) pixels[y * width + x] = color
        }
        fill(60, 60, 540, 820, PINK)
        fill(510, 120, 540, 780, PURPLE)
        fill(60, 780, 540, 820, CYAN)
        val bands = listOf(120 to 195, 225 to 300, 335 to 415, 450 to 530, 570 to 645, 685 to 765)
        for ((top, bottom) in bands) {
            fill(270, top, 509, bottom, GRAY)
            fill(370, top + (bottom - top) / 2, 500, top + (bottom - top) / 2 + 7, ORANGE)
        }
        // Short copper side compartment extends into the vertical gap before the next main rail.
        fill(90, 120, 239, 245, GRAY)
        // Large breaker starts in row 2 and spans rows 3/4; instruments below remain in row 4.
        fill(90, 280, 239, 530, GRAY)
        fun device(id: String, centerX: Float, centerY: Float) = PdfTextBox(id, centerX - 5, centerY - 5,
            10f, 10f, centerX - 5, centerY + 4, fontSize = 6f, blue = true)
        val boxes = listOf(
            device("-7FB01", 340f, 113f), // Raised identifier in the colored upper frame, row 1.
            device("-7XT01", 130f, 235f), // Side label lies nearer row 2; compartment proves row 1.
            device("-7QS01", 130f, 285f), // Tall body begins in row 2.
            device("-7FB02", 330f, 215f), // Identifier sits above row 2 in a duct.
            device("-7FB03", 400f, 245f),
            device("-7QF01", 350f, 350f),
            device("-7TA01", 110f, 520f), device("-7TA02", 155f, 485f), device("-7TA03", 200f, 510f),
            device("-7KM01", 350f, 465f),
            device("-7XT20", 330f, 590f), device("-7XT20", 390f, 590f),
            device("-7FU01", 350f, 710f),
        )
        val expected = listOf("-7XT01", "-7FB01", "-7QS01", "-7FB02", "-7FB03", "-7QF01",
            "-7TA01", "-7TA02", "-7TA03", "-7KM01", "-7XT20", "-7XT20", "-7FU01")
        return Sample(PdfPageText(1, width.toFloat(), height.toFloat(), boxes), AbInstallationOrder.Raster(width, height, pixels), expected)
    }

    private fun turned(sample: Sample, clockwise: Int): Sample {
        if (clockwise == 0) return sample
        val original = sample.raster
        val width = if (clockwise % 180 == 0) original.width else original.height
        val height = if (clockwise % 180 == 0) original.height else original.width
        val pixels = IntArray(original.pixels.size)
        for (y in 0 until original.height) for (x in 0 until original.width) {
            val point = when (clockwise) {
                90 -> original.height - 1 - y to x
                180 -> original.width - 1 - x to original.height - 1 - y
                else -> y to original.width - 1 - x
            }
            pixels[point.second * width + point.first] = original.pixels[y * original.width + x]
        }
        val boxes = sample.page.boxes.map { box ->
            val first = EplanParsing.rotate(box.x, box.y, sample.page.width, sample.page.height, clockwise)
            val last = EplanParsing.rotate(box.right, box.bottom, sample.page.width, sample.page.height, clockwise)
            val baseline = EplanParsing.rotate(box.baselineX, box.baselineY, sample.page.width, sample.page.height, clockwise)
            box.copy(x = minOf(first.first, last.first), y = minOf(first.second, last.second),
                width = kotlin.math.abs(last.first - first.first), height = kotlin.math.abs(last.second - first.second),
                baselineX = baseline.first, baselineY = baseline.second, angle = (box.angle + clockwise) % 360)
        }
        return Sample(PdfPageText(1, width.toFloat(), height.toFloat(), boxes), AbInstallationOrder.Raster(width, height, pixels), sample.expected)
    }

    private companion object {
        val WHITE = 0xffffffff.toInt()
        val BLACK = 0xff222222.toInt()
        val GRAY = 0xffaaaaaa.toInt()
        val PINK = 0xffc39191.toInt()
        val CYAN = 0xff91aaaa.toInt()
        val PURPLE = 0xff9191c3.toInt()
        val ORANGE = 0xffff9900.toInt()
    }
}
