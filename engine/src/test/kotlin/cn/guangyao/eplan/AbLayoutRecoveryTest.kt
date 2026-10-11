package cn.guangyao.eplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

class AbLayoutRecoveryTest {
    @Test fun missingLeaderNeedsAnInstallationBlockAndKeepsTheLegacyCandidatePathUnchanged() {
        val marked = vertical("-7QF01", 600f, 164.415f)
        val copper = vertical("7XT01", 467.680f, 164.415f, length = 11.811f)
        val caption = PdfTextBox("接地铜排", 462.090f, 194.966f, 10.022f, 10.022f,
            462.373f, 196.688f, 45, 1.842f, true)
        val model = caption.copy(text = "JDG-C-13", x = 465.869f, baselineX = 466.152f, blue = false)
        val bareReference = vertical("G01", 620f, 240f)
        val axisReference = caption.copy(text = "UM77/78", x = 650f, baselineX = 650f)
        val bareModel = vertical("MODEL123", 680f, 240f).copy(blue = false)
        val fields = listOf(area(463.180f, 152.604f, 474.519f, 205.271f),
            area(474.519f, 152.604f, 480.188f, 205.271f), area(457.511f, 152.604f, 463.180f, 205.271f))
        val page = PdfPageText(1, 1202f, 853f, listOf(marked, copper, caption, model, bareReference, axisReference, bareModel))
        assertEquals(listOf(marked), AbLayoutAnalysis.layoutDevices(page, fields))
        assertEquals(setOf(marked, copper), AbLayoutAnalysis.layoutDevices(page, fields, recoverUnmarkedIds = true).toSet())
        assertEquals(listOf(marked), AbLayoutAnalysis.layoutDevices(page, recoverUnmarkedIds = true))
        val functions = AbLayoutAnalysis.associateFunctions(page, listOf(marked, copper), linkAreas = fields)
        assertEquals("接地铜排", functions.getValue(copper).single().text)
    }

    @Test fun propertyCaptionOrModelDoesNotBecomeAnUnmarkedPhysicalDevice() {
        val main = vertical("-5PS01", 467.680f, 164.415f)
        // These resemble an ID, but sit in the descriptor's lower part, not at a new body head.
        val reference = vertical("G01", 461f, 199f, length = 7f)
        val axisReference = reference.copy(text = "UM77/78", x = 475f, baselineX = 478f)
        val otherCaption = PdfTextBox("设备功能", 458f, 202f, 15f, 2f, 458f, 204f, fontSize = 1.5f, blue = true)
        val fields = listOf(area(463.180f, 152.604f, 474.519f, 205.271f),
            area(474.519f, 152.604f, 480.188f, 205.271f), area(457.511f, 152.604f, 463.180f, 205.271f))
        val page = PdfPageText(1, 1202f, 853f, listOf(main, reference, axisReference, otherCaption))
        assertEquals(listOf(main), AbLayoutAnalysis.layoutDevices(page, fields, recoverUnmarkedIds = true))
    }

    @Test fun numberedSignalBanksBindToFourActualDevicesAndKeepAllFifteenNativeRuns() {
        val sample = signalSample()
        for (reverse in listOf(false, true)) {
            val page = sample.page.copy(boxes = if (reverse) sample.page.boxes.reversed() else sample.page.boxes)
            val devices = if (reverse) sample.devices.reversed() else sample.devices
            val result = AbLayoutAnalysis.associateFunctions(page, devices, linkAreas = sample.fields)
            assertEquals(15, result.values.sumOf { it.size })
            sample.devices.forEachIndexed { index, device ->
                assertEquals(sample.expected[index], result.getValue(device).map { it.text })
            }
            assertFalse(result.values.flatten().any { it.text.contains("辅助输2出") || it.text.count { char -> char == '：' || char == ':' } > 1 })
        }
    }

    @Test fun signalOwnershipUsesDisplayedGeometryInEveryQuarterTurn() {
        val original = signalSample()
        for (angle in listOf(90, 180, 270)) {
            fun turn(box: PdfTextBox): PdfTextBox {
                val a = EplanParsing.rotate(box.x, box.y, original.page.width, original.page.height, angle)
                val b = EplanParsing.rotate(box.right, box.bottom, original.page.width, original.page.height, angle)
                val anchor = EplanParsing.rotate(box.baselineX, box.baselineY, original.page.width, original.page.height, angle)
                return box.copy(x = min(a.first, b.first), y = min(a.second, b.second), width = abs(a.first - b.first),
                    height = abs(a.second - b.second), baselineX = anchor.first, baselineY = anchor.second, angle = (box.angle + angle) % 360)
            }
            val rotated = original.page.copy(width = if (angle == 180) original.page.width else original.page.height,
                height = if (angle == 180) original.page.height else original.page.width, boxes = original.page.boxes.map(::turn))
            val devices = original.devices.map(::turn)
            val result = AbLayoutAnalysis.associateFunctions(rotated, devices, linkAreas = original.fields.map(::turn))
            devices.forEachIndexed { index, device -> assertEquals(original.expected[index], result.getValue(device).map { it.text }) }
        }
    }

    @Test fun missingMiddleHitRectangleCannotStealTheNextSignalBankThroughItsMirroredSideField() {
        val original = signalSample()
        val missingMiddle = original.fields.filterNot { abs(it.x - 744.762f) < .01f && it.width > 10f }
        val mirroredSides = listOf(area(728.837f, 229.945f, 735.037f, 296.987f),
            area(742.738f, 230.268f, 748.408f, 280.509f), area(756.101f, 230.268f, 761.770f, 280.509f))
        for (angle in listOf(0, 90, 180, 270)) for (reverse in listOf(false, true)) {
            fun turn(box: PdfTextBox): PdfTextBox {
                val a = EplanParsing.rotate(box.x, box.y, original.page.width, original.page.height, angle)
                val b = EplanParsing.rotate(box.right, box.bottom, original.page.width, original.page.height, angle)
                val anchor = EplanParsing.rotate(box.baselineX, box.baselineY, original.page.width, original.page.height, angle)
                return box.copy(x = min(a.first, b.first), y = min(a.second, b.second), width = abs(a.first - b.first),
                    height = abs(a.second - b.second), baselineX = anchor.first, baselineY = anchor.second, angle = (box.angle + angle) % 360)
            }
            val fields = (missingMiddle + mirroredSides).map(::turn)
            val devices = original.devices.map(::turn)
            val page = original.page.copy(width = if (angle % 180 == 0) original.page.width else original.page.height,
                height = if (angle % 180 == 0) original.page.height else original.page.width, boxes = original.page.boxes.map(::turn))
            val result = AbLayoutAnalysis.associateFunctions(page, devices, linkAreas = if (reverse) fields.reversed() else fields)
            devices.forEachIndexed { i, device ->
                assertEquals(original.expected[i], result.getValue(device).map { it.text })
            }
        }
    }

    @Test fun standaloneNumberedChannelsNeverMergeEvenWhenTheGenericContinuationGeometryMatches() {
        val device = PdfTextBox("-9KA01", 100f, 100f, 30f, 10f, fontSize = 8f)
        val captions = listOf("1：很长的第一路输出控制信号", "2:另一条独立控制信号", "3：备用").mapIndexed { i, text ->
            PdfTextBox(text, 100f, 114f + i * 10f, text.length * 5f, 8f, 100f, 122f + i * 10f, fontSize = 8f, blue = true)
        }
        val page = PdfPageText(1, 500f, 400f, listOf(device) + captions)
        val result = AbLayoutAnalysis.associateFunctions(page, listOf(device))
        assertEquals(captions.map { it.text }, result.getValue(device).map { it.text })
    }

    @Test fun abDisplayNormalizationRemovesOnlyTheLeadingDeviceMark() {
        assertEquals("1KM03", AbLayoutAnalysis.normalizeAbDisplayText("-1KM03"))
        assertEquals("1KM04", AbLayoutAnalysis.normalizeAbDisplayText("-1KM04"))
        assertEquals("XT01-2.1", AbLayoutAnalysis.normalizeAbDisplayText("-XT01-2.1"))
        assertEquals("UM77/78", AbLayoutAnalysis.normalizeAbDisplayText("-UM77/78"))
        listOf("IO-LINK", "电源-N24", "1-2外设-P24", "-24V", "A-B控制", "文件-1.pdf", "(6A)STO").forEach {
            assertEquals(it, AbLayoutAnalysis.normalizeAbDisplayText(it))
        }
        val source = vertical("-1KM03", 100f, 100f)
        AbLayoutAnalysis.normalizeAbDisplayText(source.text)
        assertEquals("-1KM03", source.text)
    }

    private data class Sample(val page: PdfPageText, val devices: List<PdfTextBox>, val fields: List<PdfTextBox>, val expected: List<List<String>>)

    private fun signalSample(): Sample {
        // Positions and paired property rectangles from independently inspected native runs.
        // Identifiers and page number intentionally differ from the source fixture.
        val expected = listOf(
            listOf("1：急停辅助输出", "2：安全光栅辅助输出", "3：安全门禁辅助输出", "4：三段式开关辅助输出",
                "5：正常模式辅助输出", "6：维修模式辅助输出", "7：安全复位", "8：备用"),
            listOf("1:冷水机启动", "2：除尘机启动"),
            listOf("1：冷水机报警", "2：除尘机报警"),
            listOf("1：NG缓存D调速电机1", "2：NG缓存D调速电机2", "3：NG缓存D调速电机3"),
        )
        val devices = listOf(vertical("-8KA91", 718.230f, 244.118f), vertical("-8KA72", 737.387f, 244.441f),
            vertical("-8KA53", 750.750f, 244.441f), vertical("-8KA34", 764.112f, 244.441f))
        val starts = listOf(710.234f to 277.454f, 729.392f to 277.147f, 742.754f to 277.147f, 756.117f to 277.147f)
        val captions = expected.flatMapIndexed { group, texts -> texts.mapIndexed { i, text ->
            val x = starts[group].first; val y = starts[group].second + i * 2.3622f
            PdfTextBox(text, x, y - 1.535f, text.length * 2f, 1.535f, x, y, fontSize = 1.535f, blue = true)
        } }
        val fields = listOf(
            area(716.435f, 229.945f, 728.837f, 296.987f), area(710.234f, 229.945f, 716.435f, 296.987f),
            area(731.400f, 230.268f, 742.738f, 280.509f), area(725.731f, 230.268f, 731.400f, 280.509f),
            area(744.762f, 230.268f, 756.101f, 280.509f), area(739.093f, 230.268f, 744.762f, 280.509f),
            area(761.668f, 230.268f, 773.007f, 283.204f), area(755.999f, 230.268f, 761.668f, 283.204f),
            // Tiny overlapping pin hitboxes cannot replace the full numbered-signal bank.
            area(716.126f, 265.047f, 721.795f, 270.716f), area(729.223f, 265.047f, 734.892f, 270.716f),
        )
        return Sample(PdfPageText(4, 1202f, 853f, devices + captions), devices, fields, expected)
    }

    private fun vertical(text: String, x: Float, baseline: Float, length: Float = 14.173f) =
        PdfTextBox(text, x - 3.071f, baseline - length, 3.071f, length, x, baseline, 270, 3.071f, true, length)

    private fun area(left: Float, top: Float, right: Float, bottom: Float) = PdfTextBox("", left, top, right - left, bottom - top)
}
