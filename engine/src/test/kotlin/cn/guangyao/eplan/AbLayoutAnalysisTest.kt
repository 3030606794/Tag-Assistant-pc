package cn.guangyao.eplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AbLayoutAnalysisTest {
    @Test fun wideInstallationColumnsUseTheirPropertyBlocksInsteadOfCaptionDistance() {
        val sample = wideInstallationSample()
        val devices = AbLayoutAnalysis.layoutDevices(sample.first, sample.second)
        assertEquals(1, devices.count { it.text == "-1KM07" })
        assertEquals(setOf("-1XS02", "-1XS01", "-1QF08", "-1KM07"), devices.map { it.text }.toSet())
        val functions = AbLayoutAnalysis.associateFunctions(sample.first, devices, linkAreas = sample.second)
        fun function(id: String) = functions.getValue(devices.single { it.text == id }).map { it.text }
        assertEquals(listOf("二孔插座"), function("-1XS02"))
        assertEquals(listOf("三孔插座"), function("-1XS01"))
        assertEquals(listOf("D机架控制电源+C机架控制电源"), function("-1KM07"))
        assertEquals(listOf("-1KM07"), function("-1QF08"))
    }

    @Test fun nativeReaderSmallerFontEstimateDoesNotBreakPropertyMatching() {
        val sample = wideInstallationSample()
        // The old reader multiplied a 45-degree matrix component by .65, shrinking diagonal
        // fonts again by sqrt(2). The corrected reader uses the full matrix-vector magnitude.
        for (diagonalScale in listOf(.65f, .65f / kotlin.math.sqrt(2f))) {
            val page = sample.first.copy(boxes = sample.first.boxes.map {
                it.copy(fontSize = it.fontSize * if (it.angle % 90 == 0) .65f else diagonalScale)
            })
            val devices = AbLayoutAnalysis.layoutDevices(page, sample.second)
            assertEquals(1, devices.count { it.text == "-1KM07" })
            val mapped = AbLayoutAnalysis.associateFunctions(page, devices, linkAreas = sample.second)
            assertEquals("二孔插座", mapped.getValue(devices.single { it.text == "-1XS02" }).single().text)
            assertEquals("D机架控制电源+C机架控制电源", mapped.getValue(devices.single { it.text == "-1KM07" }).single().text)
            assertEquals("-1KM07", mapped.getValue(devices.single { it.text == "-1QF08" }).single().text)
        }
    }

    @Test fun middleDoubleCellAndLeftHandCaptionKeepAllFivePhysicalXt01Positions() {
        val terminals = (0..3).map { index ->
            val y = 529.460f + index * 38.0975f
            PdfTextBox("-1XT01", 769.794f, y, 25.512f, 8.504f, 769.794f, y + 6.505f, fontSize = 5.528f, blue = true)
        }
        val labels = listOf("L3", "L2", "L1", "N1").mapIndexed { index, phase ->
            val baseline = 539.413f + index * 38.0975f
            PdfTextBox("【一】电源端子-$phase", 872.306f, baseline - 29f, 32f, 29.339f,
                874.373f, baseline, 315, 2.211f, true)
        }
        val copper = PdfTextBox("-1XT01", 295.37f, 629.063f, 25.512f, 8.504f, 295.37f, 636.368f, fontSize = 5.528f, blue = true)
        val copperLabel = PdfTextBox("铜排", 231.096f, 629f, 10f, 8.5f, 233.162f, 637.129f, 315, 2.211f, true)
        val areas = (0..3).flatMap { index ->
            val top = 473.588f + index * 38.0975f
            val bottom = 540.430f + index * 38.0975f
            listOf(area(769.794f, top, 810.174f, bottom), area(810.174f, top, 890.934f, bottom), area(890.934f, top, 931.314f, bottom))
        } + listOf(area(296.246f, 626.305f, 320.882f, 651.657f), area(222.338f, 626.305f, 246.974f, 651.657f))
        val devices = terminals + copper
        val pdf = PdfPageText(164, 1202f, 853f, devices + labels + copperLabel)
        val result = AbLayoutAnalysis.associateFunctions(pdf, devices, linkAreas = areas)
        assertEquals(5, result.size)
        terminals.forEachIndexed { index, device -> assertEquals(labels[index].text, result.getValue(device).single().text) }
        assertEquals("铜排", result.getValue(copper).single().text)
    }

    @Test fun overlappingPinHitboxesDoNotHideEitherTerminalCaptionOrItsMainField() {
        val main = PdfTextBox("-1XT55", 934.837f, 337.591f, 25.512f, 8.504f,
            934.837f, 344.896f, fontSize = 5.528f, blue = true)
        val captions = listOf(346.192f, 336.780f).map { baseline ->
            PdfTextBox("接触器控制", 1007.174f, baseline - 14.092f, 16.837f, 14.431f,
                1009.24f, baseline, 315, 2.211f, true)
        }
        val areas = listOf(area(934.837f, 323.956f, 957.516f, 346.870f), area(1002.88f, 323.956f, 1025.56f, 346.870f),
            area(1009.9f, 327.442f, 1015.57f, 333.112f), area(1009.9f, 334.586f, 1015.57f, 340.256f),
            area(943f, 337f, 951f, 346.5f))
        val result = AbLayoutAnalysis.associateFunctions(PdfPageText(164, 1202f, 853f, listOf(main) + captions), listOf(main), linkAreas = areas)
        assertEquals(listOf("接触器控制", "接触器控制"), result.getValue(main).map { it.text })
    }

    @Test fun onePrintedTerminalCanOwnFourPhaseLabelsWithOnlyOneFullPropertyBlock() {
        val (pdf, areas) = verticalPhaseSample()
        val device = pdf.boxes.first()
        val result = AbLayoutAnalysis.associateFunctions(pdf, listOf(device), linkAreas = areas)
        assertEquals(setOf("【二】电源端子-L3", "【二】电源端子-L2", "【二】电源端子-L1", "【二】电源端子-N1"), result.getValue(device).map { it.text }.toSet())
        assertEquals(4, result.getValue(device).size)
    }

    @Test fun phaseExtensionCannotBorrowAnotherActualTerminalWithoutCaptionHitbox() {
        val (pdf, areas) = verticalPhaseSample()
        val first = pdf.boxes.first()
        // Same printed ID, same installation column, no hyperlink for this second position.
        // Its upper phases exceed the wide-gap distance limit and are still unassigned.
        val other = first.copy(y = first.y - 35f, baselineY = first.baselineY - 35f)
        val result = AbLayoutAnalysis.associateFunctions(pdf.copy(boxes = pdf.boxes + other), listOf(first, other), linkAreas = areas)
        assertEquals(setOf("【二】电源端子-L1", "【二】电源端子-N1"), result.getValue(first).map { it.text }.toSet())
        assertTrue(result[other].isNullOrEmpty())
    }

    @Test fun adjacentProvenPhaseFamilyBoundsTheCaptionsInsteadOfCuttingAtMainLabelMidpoint() {
        val (pdf, areas) = verticalPhaseSample()
        val main = pdf.boxes.first()
        // 663 p164: XT30 has a main ID per phase, but XT20 prints one ID below all four.
        // Its L3 baseline (403.116) is above the main-ID midpoint (~411), yet clearly below
        // XT30's own confirmed N1 caption (377.092). It still belongs to XT20.
        val neighbor = PdfTextBox("-1XT30", 783.653f, 366.340f, 25.512f, 8.504f,
            783.653f, 373.645f, fontSize = 5.528f, blue = true)
        val neighborCaption = PdfTextBox("【三】电源端子-N1", 857.354f, 343.325f, 34.275f, 34.275f,
            860.453f, 377.092f, 315, 3.317f, true)
        val neighborAreas = listOf(area(783.653f, 332.915f, 811.676f, 378.110f), area(867.722f, 332.915f, 895.745f, 378.110f))
        val result = AbLayoutAnalysis.associateFunctions(pdf.copy(boxes = pdf.boxes + neighbor + neighborCaption),
            listOf(main, neighbor), linkAreas = areas + neighborAreas)
        assertEquals(4, result.getValue(main).size)
        assertTrue(result.getValue(main).any { it.text == "【二】电源端子-L3" })
        assertEquals("【三】电源端子-N1", result.getValue(neighbor).single().text)
    }

    @Test fun anotherFamilyInsideCurrentPhaseRangeCannotDisablePhysicalBoundary() {
        val (pdf, areas) = verticalPhaseSample()
        val main = pdf.boxes.first()
        val neighbor = main.copy(text = "-1XT30", y = main.y - 35f, baselineY = main.baselineY - 35f)
        val neighborCaption = PdfTextBox("【三】电源端子-N1", 862.456f, 406f, 22.850f, 8f,
            864.522f, 412f, 315, 2.211f, true)
        val neighborAreas = listOf(area(778.55f, 405f, 805.324f, 416f), area(858.871f, 405f, 885.645f, 416f))
        val result = AbLayoutAnalysis.associateFunctions(pdf.copy(boxes = pdf.boxes + neighbor + neighborCaption),
            listOf(main, neighbor), linkAreas = areas + neighborAreas)
        assertTrue(result.getValue(main).none { it.text == "【二】电源端子-L3" })
        assertEquals("【三】电源端子-N1", result.getValue(neighbor).single().text)
    }

    @Test fun twoProvenTerminalGroupsWithIdenticalPrintedIdsDoNotMergePhaseOwnership() {
        val (pdf, areas) = verticalPhaseSample()
        val first = pdf.boxes.first()
        val other = first.copy(y = first.y - 35f, baselineY = first.baselineY - 35f)
        val upperLabel = pdf.boxes.last()
        val upperAreas = listOf(area(other.x, 380.3f, 805.324f, 416.530f), area(858.871f, 380.3f, 885.645f, 416.530f))
        val result = AbLayoutAnalysis.associateFunctions(pdf.copy(boxes = pdf.boxes + other), listOf(first, other), linkAreas = areas + upperAreas)
        assertTrue(result.getValue(first).none { it.text == upperLabel.text })
        assertTrue(result.getValue(other).any { it.text == upperLabel.text })
        assertEquals(2, AbLayoutAnalysis.installedPositions(pdf, listOf(first, other)).size)
    }

    @Test fun symmetricPropertyOwnershipNeverDependsOnInputDeviceOrder() {
        val left = PdfTextBox("-1XT01", 100f, 100f, 25f, 8f, 100f, 107f, fontSize = 5f, blue = true)
        val right = left.copy(text = "-1XT02", x = 260f, baselineX = 260f)
        val caption = PdfTextBox("控制电源", 180f, 96f, 30f, 15f, 195f, 110f, 315, 3f, true)
        val areas = listOf(area(100f, 85f, 130f, 115f), area(260f, 85f, 290f, 115f), area(180f, 85f, 210f, 115f))
        val pdf = page(listOf(left, right, caption))
        assertTrue(AbLayoutAnalysis.associateFunctions(pdf, listOf(left, right), linkAreas = areas).isEmpty())
        assertTrue(AbLayoutAnalysis.associateFunctions(pdf, listOf(right, left), linkAreas = areas).isEmpty())
    }

    @Test fun denseFuseCaptionRowsBindAcrossWideGapWithoutSlidingToNeighbor() {
        val ids = listOf("-1FU07", "-1FU08", "-1FU09")
        val captions = listOf("安全总电源", "IO电源", "IO-LINK")
        val devices = ids.mapIndexed { index, id ->
            val y = 410.2765f + index * 3.458f
            PdfTextBox(id, 944.407f, y, 12.756f, 4.252f, 944.407f, y + 3.6525f, fontSize = 2.764f, blue = true)
        }
        val functions = captions.mapIndexed { index, label ->
            val y = 410.2765f + index * 3.458f
            PdfTextBox(label, 1010.110f, y, 12.756f, 4.252f, 1010.110f, y + 3.6525f, fontSize = 2.764f, blue = true)
        }
        val areas = devices.flatMap { device -> listOf(
            area(944.407f, device.y - .2995f, 964.022f, device.y + 4.5525f),
            area(1003.25f, device.y - .2995f, 1022.870f, device.y + 4.5525f),
        ) }
        val pdf = PdfPageText(164, 1202f, 853f, devices + functions)
        val mapped = AbLayoutAnalysis.associateFunctions(pdf, devices, linkAreas = areas)
        devices.forEachIndexed { index, device -> assertEquals(captions[index], mapped.getValue(device).single().text) }
    }

    @Test fun angledIdentifierWithoutConfirmedModelFieldRemainsAnInstalledCandidate() {
        val first = PdfTextBox("-KM01", 100f, 100f, 30f, 9f, 100f, 108f, fontSize = 8f, blue = true)
        val actualRotated = PdfTextBox("-KM01", 185f, 96f, 18f, 18f, 186f, 111f, 315, 4.8f, true)
        val areas = listOf(area(100f, 85f, 135f, 114f), area(175f, 85f, 210f, 114f))
        val pdf = page(listOf(first, actualRotated))
        assertEquals(2, AbLayoutAnalysis.layoutDevices(pdf, areas).size)
    }

    @Test fun twoRealIdenticalMainIdentifiersAreNeverRemovedByMatchingPropertyBlocks() {
        val first = PdfTextBox("-1XT20", 100f, 100f, 30f, 9f, 100f, 108f, fontSize = 8f, blue = true)
        val second = first.copy(x = 180f, baselineX = 180f)
        val areas = listOf(area(100f, 85f, 135f, 114f), area(180f, 85f, 215f, 114f))
        assertEquals(2, AbLayoutAnalysis.layoutDevices(page(listOf(first, second)), areas).size)
    }

    @Test fun cancellationEscapesEveryProcessingEntryPointDuringIteration() {
        val device = box("-FU01", 100f, 100f, 40f)
        val sample = tablePage(listOf(device, box("控制电源", 420f, 100f, blue = true)))
        val cancellation = IllegalStateException("cancelled")
        val operations: List<((() -> Unit) -> Unit)> = listOf(
            { check -> AbLayoutAnalysis.layoutDevices(sample, checkCancelled = check); Unit },
            { check -> AbLayoutAnalysis.functionTable(sample, check); Unit },
            { check -> AbLayoutAnalysis.installedPositions(sample, listOf(device), checkCancelled = check); Unit },
            { check -> AbLayoutAnalysis.associateFunctions(sample, listOf(device), checkCancelled = check); Unit },
        )
        for (operation in operations) {
            var checks = 0
            val thrown = assertThrows(IllegalStateException::class.java) {
                operation { if (++checks == 2) throw cancellation }
            }
            assertSame(cancellation, thrown)
            assertEquals(2, checks)
        }
    }

    @Test fun continuationPanelUsesItsOwnDeviceAndFunctionColumns() {
        val page = tablePage(listOf(
            box("-1FU03", 90f, 100f, 40f), box("MODEL_24V", 210f, 100f), box("供应商甲", 330f, 100f), box("IO-LINK", 420f, 100f),
            box("-1FU04", 90f, 130f, 40f), box("STO", 420f, 130f),
            box("-1XS01", 590f, 100f, 40f), box("MODEL_B", 710f, 100f), box("供应商乙", 830f, 100f), box("三孔插座", 920f, 100f),
            box("-1QF05", 590f, 130f, 40f), box("上料段-控制电源", 920f, 130f, 150f),
        ))
        val result = AbLayoutAnalysis.functionTable(page)
        assertEquals(listOf("IO-LINK"), result.getValue("1FU03").map { it.text })
        assertEquals(listOf("三孔插座"), result.getValue("1XS01").map { it.text })
        assertEquals(listOf("上料段-控制电源"), result.getValue("1QF05").map { it.text })
        assertFalse(result.values.flatten().any { it.text.contains("供应商") || it.text.contains("MODEL") })
    }

    @Test fun emptyLeftFunctionCannotStealRightPanelFunctionOnSameRow() {
        val result = AbLayoutAnalysis.functionTable(tablePage(listOf(
            box("-EP01", 90f, 100f, 40f), box("供应商甲", 330f, 100f),
            box("-1FU04", 90f, 130f, 40f), box("STO", 420f, 130f),
            box("-1XS01", 590f, 100f, 40f), box("三孔插座", 920f, 100f),
            box("-1QF05", 590f, 130f, 40f), box("控制电源", 920f, 130f),
        )))
        assertFalse(result.containsKey("EP01"))
        assertEquals("三孔插座", result.getValue("1XS01").single().text)
    }

    @Test fun completeHeadersOnBothPanelsAreSupported() {
        val page = tablePage(listOf(
            box("设备标识符", 580f, 50f, 60f), box("类型号", 700f, 50f, 60f),
            box("供应商", 820f, 50f, 60f), box("功能文本", 910f, 50f, 60f),
            box("-1FU03", 90f, 100f, 40f), box("IO-LINK", 420f, 100f),
            box("-1XS01", 590f, 100f, 40f), box("三孔插座", 920f, 100f),
        ))
        assertEquals(setOf("1FU03", "1XS01"), AbLayoutAnalysis.functionTable(page).keys)
    }

    @Test fun continuationFirstRowAboveLeftHeaderIsNotLost() {
        // India p257 and 663 p166 begin the right continuation at y=85; the left header is y=90.
        val page = tablePage(listOf(
            box("-1FU03", 90f, 100f, 40f), box("IO-LINK", 420f, 100f),
            box("-1KM13", 590f, 40f, 40f), box("F1-6-温控电源", 920f, 40f, 130f),
            box("-1QF06", 590f, 75f, 40f), box("C14控制电源", 920f, 75f, 100f),
        ))
        val result = AbLayoutAnalysis.functionTable(page)
        assertEquals("F1-6-温控电源", result.getValue("1KM13").single().text)
        assertEquals("C14控制电源", result.getValue("1QF06").single().text)
    }

    @Test fun multilineCellMergesAndSemicolonFunctionsRemainSeparate() {
        val page = tablePage(listOf(
            box("-UM109", 90f, 100f, 40f),
            box("M28通道AB升降Z轴", 420f, 96f, 150f), box("(750W+抱闸)", 420f, 108f, 110f),
            box("-1XT20", 90f, 150f, 40f), box("端子-L1;端子-L2", 420f, 150f, 140f),
            box("-1XT21", 90f, 190f, 40f), box("另一行", 420f, 190f),
        ))
        val result = AbLayoutAnalysis.functionTable(page)
        assertEquals("M28通道AB升降Z轴(750W+抱闸)", result.getValue("UM109").single().text)
        assertEquals(listOf("端子-L1", "端子-L2"), result.getValue("1XT20").map { it.text })
        assertEquals("另一行", result.getValue("1XT21").single().text)
    }

    @Test fun englishIsPermittedOnlyInConfirmedFunctionFields() {
        assertTrue(AbLayoutAnalysis.confirmedFunction("IO-LINK"))
        assertTrue(AbLayoutAnalysis.confirmedFunction("Safety power"))
        assertFalse(AbLayoutAnalysis.confirmedFunction("="))
        assertFalse(AbLayoutAnalysis.confirmedFunction("24V"))
        assertFalse(AbLayoutAnalysis.confirmedFunction("-1FU03"))
        assertFalse(EplanParsing.isFunction("NXJ/2Z(D)_DC24"))
    }

    @Test fun exactOverprintIsRemovedButFourPhysicalTerminalCopiesRemain() {
        val copies = (0..3).map { box("-1XT20", 100f + it * 20f, 100f, 5f, height = 15f, angle = 270) }
        val result = AbLayoutAnalysis.installedPositions(page(copies + copies[0].copy(x = copies[0].x + .1f)), copies + copies[0].copy(x = copies[0].x + .1f))
        assertEquals(4, result.size)
        assertEquals(listOf(100f, 120f, 140f, 160f), result.map { it.box.x })
        assertTrue(result.all { it.rotation == 0 })
    }

    @Test fun mixedVerticalAndHorizontalDeviceLegendsDoNotRotateUprightBoard() {
        val devices = listOf(box("-XT01", 100f, 100f, angle = 270), box("-XT02", 180f, 100f, angle = 270), box("-UM01", 100f, 180f))
        val result = AbLayoutAnalysis.installedPositions(page(devices), devices)
        assertEquals(listOf("-XT01", "-XT02", "-UM01"), result.map { it.box.text })
        assertTrue(result.all { it.rotation == 0 })
    }

    @Test fun longVerticalCombinedIdentifierStaysOnItsPhysicalRail() {
        val short = PdfTextBox("-UM01", 100f, 140f, 5f, 15f, 105f, 155f, 270, 5f)
        val combined = PdfTextBox("-UM11/12/13/14", 150f, 115f, 5f, 40f, 155f, 155f, 270, 5f)
        val result = AbLayoutAnalysis.installedPositions(page(listOf(combined, short)), listOf(combined, short))
        assertEquals(listOf("-UM01", "-UM11/12/13/14"), result.map { it.box.text })
    }

    @Test fun explicitRotationStillControlsPhysicalReadOrder() {
        val devices = listOf(box("-XT90", 100f, 100f), box("-XT01", 100f, 180f))
        val result = AbLayoutAnalysis.installedPositions(page(devices), devices, 90)
        assertEquals(listOf("-XT01", "-XT90"), result.map { it.box.text })
        assertTrue(result.all { it.rotation == 90 })
    }

    @Test fun separateInstallationHeadingsKeepEachBoardTogether() {
        val devices = listOf(box("-XT01", 100f, 100f), box("-XT02", 100f, 180f), box("-XT03", 600f, 100f), box("-XT04", 600f, 180f))
        val page = page(devices + box("左安装板", 90f, 60f) + box("右安装板", 590f, 60f))
        val result = AbLayoutAnalysis.installedPositions(page, devices)
        assertEquals(listOf("-XT01", "-XT02", "-XT03", "-XT04"), result.map { it.box.text })
        assertEquals(listOf(0, 0, 1, 1), result.map { it.region })
    }

    @Test fun whitespaceAloneDoesNotInventSeparateBoards() {
        val devices = listOf(box("-XT01", 100f, 100f), box("-XT02", 100f, 180f), box("-XT03", 600f, 100f), box("-XT04", 600f, 180f))
        val result = AbLayoutAnalysis.installedPositions(page(devices), devices)
        assertEquals(listOf("-XT01", "-XT03", "-XT02", "-XT04"), result.map { it.box.text })
        assertEquals(setOf(0), result.map { it.region }.toSet())
    }

    /** Coordinates transcribed from B06B07-1 PDF page 130; slanted caption boxes overlap neighbors. */
    @Test fun b06Four1Xt20InstancesKeepTheirOwnDiagonalPhaseFunctions() {
        val terminals = (0..3).map { index ->
            val shift = 14.835f * index
            PdfTextBox("-1XT20", 668.623f + shift, 307.488f, 4.724f, 14.173f,
                672.681f + shift, 321.661f, 270, 4.724f, true)
        }
        val phases = listOf("L1", "L2", "L3", "N1")
        val functions = phases.mapIndexed { index, phase ->
            val shift = 14.835f * index
            PdfTextBox("【二】级电源端子-$phase", 667.090f + shift, 355.047f, 21.046f, 21.046f,
                667.373f + shift, 356.769f, 45, 2.835f, true)
        }
        val nextRail = listOf(673.570f, 684.845f, 696.121f, 710.247f).mapIndexed { index, x ->
            PdfTextBox("-1QF${index + 9}", x, 394.700f, 4.724f, 14.173f, x + 4.058f, 408.873f, 270, 4.724f, true)
        }
        val leftNeighbor = PdfTextBox("-1QF08", 655.395f, 309.997f, 4.724f, 14.173f, 659.453f, 324.170f, 270, 4.724f, true)
        val devices = terminals + nextRail + leftNeighbor
        val result = AbLayoutAnalysis.associateFunctions(PdfPageText(130, 1202f, 853f, devices + functions), devices)
        assertEquals(4, result.size)
        terminals.forEachIndexed { index, terminal ->
            assertEquals("【二】级电源端子-${phases[index]}", result.getValue(terminal).single().text)
        }
    }

    @Test fun parentheticalContinuationBindsToItsFunctionWithoutMergingNeighbor() {
        val first = box("-UM109", 100f, 100f, 40f)
        val second = box("-UM110", 300f, 100f, 40f)
        val boxes = listOf(first, second, box("M28通道AB升降Z轴", 100f, 120f, 130f, blue = true),
            box("(750W+抱闸)", 100f, 133f, 90f, blue = true), box("另一设备功能", 300f, 120f, 90f, blue = true))
        val result = AbLayoutAnalysis.associateFunctions(page(boxes), listOf(first, second))
        assertEquals("M28通道AB升降Z轴(750W+抱闸)", result.getValue(first).single().text)
        assertEquals("另一设备功能", result.getValue(second).single().text)
    }

    @Test fun independentTerminalRangesAreNotMergedIntoOneFunction() {
        val terminal = box("-1XT57", 100f, 100f, 40f)
        val boxes = listOf(terminal, box("1-2顶端照明-N24", 100f, 120f, 100f, blue = true),
            box("3-4顶端照明-P24", 100f, 132f, 100f, blue = true))
        val result = AbLayoutAnalysis.associateFunctions(page(boxes), listOf(terminal))
        assertEquals(listOf("1-2顶端照明-N24", "3-4顶端照明-P24"), result.getValue(terminal).map { it.text })
    }

    @Test fun blackModelAndUnboundRatingNeverBecomeFunctions() {
        val device = box("-FU01", 100f, 100f, 40f)
        val boxes = listOf(device, box("NXJ/2Z(D)_DC24", 100f, 115f, 130f), box("(6A)", 100f, 128f, blue = true))
        assertTrue(AbLayoutAnalysis.associateFunctions(page(boxes), listOf(device)).isEmpty())
    }

    private fun tablePage(values: List<PdfTextBox>) = PdfPageText(95, 1200f, 850f, listOf(
        box("设备标识符", 80f, 50f, 60f), box("类型号", 200f, 50f, 60f),
        box("供应商", 320f, 50f, 60f), box("功能文本", 410f, 50f, 60f),
    ) + values)

    private fun verticalPhaseSample(): Pair<PdfPageText, List<PdfTextBox>> {
        val main = PdfTextBox("-1XT20", 778.55f, 441.401f, 25.512f, 8.504f,
            778.55f, 448.706f, fontSize = 5.528f, blue = true)
        val captions = listOf("N1" to 450.852f, "L1" to 433.390f, "L2" to 418.140f, "L3" to 403.116f).map { (phase, baseline) ->
            PdfTextBox("【二】电源端子-$phase", 862.456f, baseline - 22.511f, 22.850f, 22.850f,
                864.522f, baseline, 315, 2.211f, true)
        }
        return PdfPageText(164, 1202f, 853f, listOf(main) + captions) to listOf(
            area(778.55f, 428.341f, 805.324f, 451.530f), area(858.871f, 428.341f, 885.645f, 451.530f))
    }

    /** Original layout positions/property rectangles from 663-680-694_1 PDF page 164. */
    private fun wideInstallationSample(): Pair<PdfPageText, List<PdfTextBox>> {
        fun text(value: String, x: Float, y: Float, right: Float, bottom: Float, bx: Float, by: Float, angle: Int, font: Float, blue: Boolean = true) =
            PdfTextBox(value, x, y, right - x, bottom - y, bx, by, angle, font, blue)
        val boxes = listOf(
            text("-1XS02", 782.235f, 171.656f, 807.747f, 180.160f, 782.235f, 178.961f, 0, 8.504f),
            text("-1XS01", 782.235f, 186.963f, 807.747f, 195.467f, 782.235f, 194.268f, 0, 8.504f),
            text("二孔插座", 855.936f, 164.878f, 873.976f, 182.918f, 859.035f, 182.409f, 315, 5.102f),
            text("三孔插座", 855.936f, 180.185f, 873.976f, 198.225f, 859.035f, 197.716f, 315, 5.102f),
            text("-1KM07", 610.276f, 192.647f, 635.788f, 201.151f, 610.276f, 199.952f, 0, 8.504f),
            text("D机架控制电源+C机架控制电源", 710.622f, 151.594f, 762.937f, 203.909f, 713.721f, 203.400f, 315, 5.102f),
            text("NC1-9511Z", 710.622f, 177.261f, 730.465f, 197.105f, 713.721f, 196.596f, 315, 5.102f, false),
            text("-1QF08", 469.596f, 190.194f, 495.108f, 198.698f, 469.596f, 197.499f, 0, 8.504f),
            text("-1KM07", 545.310f, 187.024f, 559.742f, 201.456f, 548.409f, 200.947f, 315, 5.102f),
            text("NXB-125E_4P_D100", 545.310f, 162.181f, 577.781f, 194.653f, 548.409f, 194.144f, 315, 5.102f, false),
        )
        val areas = listOf(
            area(782.235f, 158.075f, 805.298f, 183.426f), area(851.422f, 158.075f, 874.484f, 183.426f),
            area(782.235f, 173.382f, 805.298f, 198.734f), area(851.422f, 173.382f, 874.484f, 198.734f),
            area(610.276f, 151.594f, 648.568f, 204.417f), area(725.153f, 151.594f, 763.446f, 204.417f),
            area(469.596f, 162.182f, 496.770f, 201.965f), area(551.116f, 162.182f, 578.290f, 201.965f),
        )
        return PdfPageText(164, 1202f, 853f, boxes) to areas
    }

    private fun area(x: Float, y: Float, right: Float, bottom: Float) = PdfTextBox("", x, y, right - x, bottom - y)

    private fun page(boxes: List<PdfTextBox>) = PdfPageText(1, 1000f, 800f, boxes)
    private fun box(text: String, x: Float, y: Float, width: Float = 70f, height: Float = 10f, angle: Int = 0, blue: Boolean = false) =
        PdfTextBox(text, x, y, width, height, x, y + height, angle, 10f, blue)
}
