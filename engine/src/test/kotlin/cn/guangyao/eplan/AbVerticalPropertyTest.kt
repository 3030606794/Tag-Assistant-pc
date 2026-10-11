package cn.guangyao.eplan

import kotlin.math.abs
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbVerticalPropertyTest {
    @Test fun repeatedTerminalIdsKeepTheirOwnPhaseInEachNarrowPropertyBlockInAllDirections() {
        val sample = repeatedTerminals()
        for (rotation in listOf(0, 90, 180, 270)) {
            val rotated = sample.rotate(rotation)
            val result = AbLayoutAnalysis.associateFunctions(rotated.page, rotated.devices, linkAreas = rotated.areas)
            rotated.devices.forEachIndexed { index, device ->
                assertEquals("Rotation $rotation, physical terminal $index", listOf(sample.labels[index].text),
                    result.getValue(device).map { it.text })
            }
        }
    }

    @Test fun longCaptionMayCrossAdjacentCellsButItsInsertionAnchorSelectsTheMainCell() {
        val sample = repeatedTerminals()
        val longLabels = sample.labels.map { it.copy(width = 64f, height = 64f) }
        val pdf = sample.page.copy(boxes = sample.devices + longLabels)
        val result = AbLayoutAnalysis.associateFunctions(pdf, sample.devices, linkAreas = sample.areas)
        sample.devices.forEachIndexed { index, device ->
            assertEquals(listOf(longLabels[index].text), result.getValue(device).map { it.text })
        }
    }

    @Test fun captionAtFootOfTerminalCannotBeAbsorbedByCloserDeviceOnNextRow() {
        val sample = repeatedTerminals()
        val nextRow = PdfTextBox("-1FL01", 124f, 196f, 4f, 14f, 128f, 210f,
            angle = 270, fontSize = 4.7f, blue = true)
        val devices = sample.devices + nextRow
        val pdf = sample.page.copy(boxes = devices + sample.labels)
        val result = AbLayoutAnalysis.associateFunctions(pdf, devices, linkAreas = sample.areas)
        sample.devices.forEachIndexed { index, device ->
            assertEquals(listOf(sample.labels[index].text), result.getValue(device).map { it.text })
        }
        assertTrue(result[nextRow].isNullOrEmpty())
    }

    @Test fun anotherPhysicalMainInsideTheLongNarrowCellPreventsStrongOwnership() {
        val sample = repeatedTerminals()
        val inside = PdfTextBox("-1FL01", 124f, 176f, 4f, 14f, 128f, 190f,
            angle = 270, fontSize = 4.7f, blue = true)
        val devices = sample.devices + inside
        val result = AbLayoutAnalysis.associateFunctions(sample.page.copy(boxes = devices + sample.labels),
            devices, linkAreas = sample.areas)
        assertTrue(result[sample.devices[1]].orEmpty().none { it.text == sample.labels[1].text })
    }

    @Test fun nativeGlyphThicknessAllowsSmallBorderRoundingInTheNarrowCellWidth() {
        val sample = repeatedTerminals()
        val devices = sample.devices.map { it.copy(width = 2.95f, fontSize = 2.95f) }
        val nextRow = PdfTextBox("-1FL01", 124f, 196f, 2.95f, 14f, 126.95f, 210f,
            angle = 270, fontSize = 2.95f, blue = true)
        val allDevices = devices + nextRow
        val result = AbLayoutAnalysis.associateFunctions(sample.page.copy(boxes = allDevices + sample.labels),
            allDevices, linkAreas = sample.areas)
        devices.forEachIndexed { index, device ->
            assertEquals(listOf(sample.labels[index].text), result.getValue(device).map { it.text })
        }
        assertTrue(result[nextRow].isNullOrEmpty())
    }

    @Test fun broadUnpairedRectangleDoesNotSupplyVerticalFieldOwnership() {
        val sample = repeatedTerminals()
        val ambiguous = sample.devices.first().copy(text = "-1XT99", x = 99f, baselineX = 103f)
        val devices = sample.devices + ambiguous
        val pdf = sample.page.copy(boxes = devices + sample.labels)
        val result = AbLayoutAnalysis.associateFunctions(pdf, devices,
            linkAreas = listOf(area(95f, 100f, 180f, 200f)))
        assertTrue(result.values.flatten().size < sample.labels.size)
    }

    @Test fun completeFourPhaseBankRecoversMissingMiddleCellInAllDirections() {
        val sample = missingMiddlePhaseBank()
        for (rotation in listOf(0, 90, 180, 270)) {
            val rotated = sample.rotate(rotation)
            val result = AbLayoutAnalysis.associateFunctions(rotated.page, rotated.devices, linkAreas = rotated.areas)
            assertEquals("Rotation $rotation", sample.labels.map { it.text }.toSet(),
                result.getValue(rotated.devices.first()).map { it.text }.toSet())
            assertTrue("Last phase must not reach the next rail", result[rotated.devices.last()].isNullOrEmpty())
        }
    }

    @Test fun completePhaseOwnershipRequiresTheWholeUnchangedPrefixAndUniformNativeTypography() {
        val sample = missingMiddlePhaseBank()
        val cases = listOf(
            sample.labels.dropLast(1),
            sample.labels.mapIndexed { index, box -> if (index == 2) box.copy(text = "【三】级电源端子-L2") else box },
            sample.labels.mapIndexed { index, box -> if (index == 2) box.copy(fontSize = box.fontSize * 1.2f) else box },
            sample.labels.mapIndexed { index, box -> if (index == 2) box.copy(baselineY = box.baselineY + 1f) else box },
            sample.labels.mapIndexed { index, box -> if (index == 2) box.copy(angle = 46) else box },
            sample.labels.map { it.copy(sourceObject = -1, sourceRun = -1, sourceSequence = -1, sourceEndSequence = -1) },
        )
        for (labels in cases) {
            val result = AbLayoutAnalysis.associateFunctions(sample.page.copy(boxes = sample.devices + labels),
                sample.devices, linkAreas = sample.areas)
            assertTrue("Partial or changed family cannot acquire all four phases",
                result[sample.devices.first()].orEmpty().size < 4)
        }
    }

    @Test fun anotherNativeFunctionOrMainIdentifierInterruptsFourPhaseOwnership() {
        val sample = missingMiddlePhaseBank()
        val interruptingFunction = PdfTextBox("独立照明电源", 300f, 145f, 20f, 3f, 300f, 148f,
            fontSize = 3f, blue = true, sourceObject = 20, sourceRun = 20,
            sourceSequence = 144, sourceEndSequence = 146)
        val interruptingDevice = interruptingFunction.copy(text = "-1QF88")
        for (interrupt in listOf(interruptingFunction, interruptingDevice)) {
            val devices = sample.devices + if (interrupt == interruptingDevice) listOf(interrupt) else emptyList()
            val result = AbLayoutAnalysis.associateFunctions(sample.page.copy(boxes = devices + sample.labels + interrupt),
                devices, linkAreas = sample.areas)
            assertTrue(result[sample.devices.first()].orEmpty().size < 4)
        }
    }

    @Test fun anAdditionalPhysicalMainInsideRecoveredBlockOrTwoPossibleBlocksPreventsGroupBinding() {
        val sample = missingMiddlePhaseBank()
        val other = sample.devices.first().copy(text = "-1XT91", x = 110f, y = 120f,
            baselineX = 113f, baselineY = 134f, sourceObject = 9, sourceRun = 9,
            sourceSequence = 80, sourceEndSequence = 86)
        val withOther = AbLayoutAnalysis.associateFunctions(sample.page.copy(boxes = sample.page.boxes + other),
            sample.devices + other, linkAreas = sample.areas)
        assertTrue(withOther[sample.devices.first()].orEmpty().size < 4)

        val almostSameSides = sample.areas.map { it.copy(x = it.x + .1f, baselineX = it.baselineX + .1f) }
        val ambiguous = AbLayoutAnalysis.associateFunctions(sample.page, sample.devices,
            linkAreas = sample.areas + almostSameSides)
        assertTrue(ambiguous[sample.devices.first()].orEmpty().size < 4)
    }

    @Test fun fourPhaseGroupCannotReachPastOneCaptionFontAtTheContainerBoundary() {
        val sample = missingMiddlePhaseBank()
        val labels = sample.labels.mapIndexed { index, box ->
            if (index == 3) box.copy(x = box.x + 2f, baselineX = box.baselineX + 2f) else box
        }
        val result = AbLayoutAnalysis.associateFunctions(sample.page.copy(boxes = sample.devices + labels),
            sample.devices, linkAreas = sample.areas)
        assertTrue(result[sample.devices.first()].orEmpty().size < 4)
    }

    private data class Sample(
        val page: PdfPageText,
        val devices: List<PdfTextBox>,
        val labels: List<PdfTextBox>,
        val areas: List<PdfTextBox>,
    ) {
        fun rotate(rotation: Int): Sample {
            fun turn(box: PdfTextBox): PdfTextBox {
                val a = EplanParsing.rotate(box.x, box.y, page.width, page.height, rotation)
                val b = EplanParsing.rotate(box.right, box.bottom, page.width, page.height, rotation)
                val anchor = EplanParsing.rotate(box.baselineX, box.baselineY, page.width, page.height, rotation)
                return box.copy(x = min(a.first, b.first), y = min(a.second, b.second),
                    width = abs(a.first - b.first), height = abs(a.second - b.second),
                    baselineX = anchor.first, baselineY = anchor.second, angle = (box.angle + rotation) % 360)
            }
            val ids = devices.map(::turn)
            val captions = labels.map(::turn)
            return Sample(page.copy(boxes = ids + captions), ids, captions, areas.map(::turn))
        }
    }

    private fun repeatedTerminals(): Sample {
        val devices = (0..3).map { index ->
            val x = 100f + index * 20f
            PdfTextBox("-1XT10", x + 3f, 100f, 4f, 14f, x + 7f, 114f,
                angle = 270, fontSize = 4.7f, blue = true)
        }
        val labels = listOf("N1", "L1", "L2", "L3").mapIndexed { index, phase ->
            val x = 100f + index * 20f
            PdfTextBox("【一】电源级端子-$phase", x + 2f, 169f, 22f, 22f,
                x + 2f, 170f, angle = 45, fontSize = 2.36f, blue = true)
        }
        val areas = (0..3).flatMap { index ->
            val x = 100f + index * 20f
            listOf(area(x, 100f, x + 6f, 192f), area(x + 6f, 100f, x + 18f, 192f),
                area(x + 18f, 100f, x + 24f, 192f))
        }
        return Sample(PdfPageText(1, 500f, 500f, devices + labels), devices, labels, areas)
    }

    private fun missingMiddlePhaseBank(): Sample {
        val terminal = PdfTextBox("-1XT90", 106.472f, 100f, 3.071f, 14.173f,
            109.543f, 114.173f, angle = 270, fontSize = 3.071f, blue = true,
            sourceObject = 10, sourceRun = 10, sourceSequence = 100, sourceEndSequence = 106)
        val nextRail = PdfTextBox("-1QF99", 127.546f, 183.626f, 3.071f, 14.173f,
            130.617f, 197.799f, angle = 270, fontSize = 3.071f, blue = true,
            sourceObject = 30, sourceRun = 30, sourceSequence = 200, sourceEndSequence = 206)
        val starts = listOf(105.274f, 114.975f, 119.542f, 124.030f)
        val sequences = listOf(108, 130, 148, 166)
        val runs = listOf(12, 18, 21, 24)
        val labels = listOf("N1", "L1", "L2", "L3").mapIndexed { index, phase ->
            PdfTextBox("【三】电源端子-$phase", starts[index], 144.948f, 13f, 13f,
                starts[index], 145.228f, angle = 45, fontSize = 1.2285f, blue = true,
                sourceObject = runs[index], sourceRun = runs[index], sourceSequence = sequences[index],
                sourceEndSequence = sequences[index] + 12)
        }
        val areas = listOf(area(100f, 100f, 105.669f, 157.828f),
            area(117.008f, 100f, 122.677f, 157.828f))
        val devices = listOf(terminal, nextRail)
        return Sample(PdfPageText(1, 500f, 500f, devices + labels), devices, labels, areas)
    }

    private fun area(left: Float, top: Float, right: Float, bottom: Float) =
        PdfTextBox("", left, top, right - left, bottom - top)
}
