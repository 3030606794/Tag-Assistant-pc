package cn.guangyao.eplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class EplanParsingTest {
    @Test fun cn1RetainsOriginalEndpointOrderAndSigns() {
        val text = "2XT71:7.2/-UM10:CN1:13"
        val wire = EplanParsing.cn1Wire(text)!!
        assertEquals(text, wire.text)
        assertEquals("UM10", wire.um)
        assertNotNull(EplanParsing.cn1Wire("UM10:CN1:5/2KA01:-1"))
    }

    @Test fun cn1CombinedAxesAreOneGroupInEitherEndpointOrder() {
        assertEquals("UM11/UM12", EplanParsing.cn1Wire("XT71:12.2/UM11/UM12:CN1:5")!!.um)
        assertEquals("UM11/12", EplanParsing.cn1Wire("UM11/12:CN1:5/XT71:12.2")!!.um)
        assertEquals(4, EplanParsing.umAxisCount("UM61/62/63/64"))
    }

    @Test fun cn1RejectsPinReferencesFunctionsAndIncompleteOppositeEnds() {
        listOf("-UM10:CN1:4", "UM10:CN1", "CN1/抱闸", "1-6CN1/抱闸-N24", "UM10:CN1:4 / 3.2", "/UM10:CN1:4", "UM10:CN7:4/XT01:1").forEach {
            assertNull(it, EplanParsing.cn1Wire(it))
        }
        assertEquals("UM10:CN1:4 / XT01:3.2", EplanParsing.cn1Wire("UM10:CN1:4 / XT01:3.2")!!.text)
    }

    @Test fun deviceIdentityPreservesInternalPunctuationAndPhysicalDuplicates() {
        assertEquals("R30/31", EplanParsing.deviceId("-R30/31"))
        assertEquals("XT01-2.1", EplanParsing.deviceId("-XT01-2.1"))
        assertEquals("1PM01", EplanParsing.deviceId("-1 P M 01"))
        assertNull(EplanParsing.deviceId("UM10")) // Could be another component's function text.
        assertNull(EplanParsing.deviceId("-UM10:CN1:4"))
        val copies = listOf(box("-XT01", 10f, 10f), box("-XT01", 40f, 10f))
        assertEquals(2, EplanParsing.readingOrder(copies, 200f, 200f).size)
    }

    @Test fun normalRowsKeepSlightlyRaisedLastDeviceAtRight() {
        val left = box("-UM01", 20f, 100f)
        val middle = box("-UM02", 60f, 101f)
        val rightRaised = box("-UM03", 110f, 96f)
        val nextRow = box("-XT01", 20f, 150f)
        assertEquals(listOf("-UM01", "-UM02", "-UM03", "-XT01"),
            EplanParsing.readingOrder(listOf(rightRaised, nextRow, middle, left), 200f, 200f).map { it.text })
    }

    @Test fun correctedCoordinatesDetermineOrderInsteadOfIdentifierNumbers() {
        val top = box("-XT90", 10f, 10f)
        val bottom = box("-XT01", 10f, 70f)
        assertEquals(listOf("-XT01", "-XT90"), EplanParsing.readingOrder(listOf(top, bottom), 100f, 100f, 90).map { it.text })
    }

    @Test fun rowToleranceCannotChainAcrossSeparateRows() {
        val values = listOf(box("first", 20f, 10f), box("middle", 40f, 20f), box("next", 5f, 30f))
        assertEquals(listOf("first", "middle", "next"), EplanParsing.readingOrder(values, 100f, 100f).map { it.text })
    }

    @Test fun joinsDiagonalGlyphsWithoutBlackModelContamination() {
        val angle = Math.toRadians(45.0)
        val glyphs = "STO".mapIndexed { i, char ->
            val x = 20f + (i * 6 * cos(angle)).toFloat()
            val y = 20f + (i * 6 * sin(angle)).toFloat()
            PdfTextBox(char.toString(), x, y - 6, 8f, 8f, x, y, 45, 8f, true, 6f)
        } + PdfTextBox("MODEL_24V", 35f, 32f, 60f, 8f, 35f, 40f, 45, 8f, false)
        val joined = EplanParsing.joinGlyphs(glyphs)
        assertEquals("STO", joined.single { it.blue }.text)
        assertFalse(joined.single { it.blue }.text.contains("MODEL"))
    }

    @Test fun separateInstalledLocationsAreNeverGloballyDeduplicated() {
        val input = listOf(
            PdfTextBox("-XT01", 10f, 10f, 30f, 8f, 10f, 18f, fontSize = 8f),
            PdfTextBox("-XT01", 100f, 10f, 30f, 8f, 100f, 18f, fontSize = 8f),
        )
        assertEquals(2, EplanParsing.joinGlyphs(input).size)
    }

    @Test fun pageSelectorsAcceptNativeLayoutTypeWithoutLiteralLayoutTitle() {
        assertTrue(EplanParsing.isLayout(PdfPageScope(1, "主柜", type = "ELU1", title = "主柜-伺服")))
        assertFalse(EplanParsing.isLayout(PdfPageScope(2, "主柜", type = "ELU1", title = "安装板孔位图")))
        assertFalse(EplanParsing.isLayout(PdfPageScope(3, "主柜", type = "ELU1", title = "功能文本表")))
        assertFalse(EplanParsing.isLayout(PdfPageScope(4, "主柜", type = "EPA1", title = "部件汇总表")))
        assertFalse(EplanParsing.isWiring(PdfPageScope(1, type = "ELU1", title = "安装布局")))
        assertTrue(EplanParsing.isWiring(PdfPageScope(1, type = "EFSI", title = "驱动器接线")))
    }

    @Test fun singleModesReadOnlyTheirDependencyClasses() {
        assertFalse(EplanParsing.needsLayout(ExtractionMode.CN1))
        assertFalse(EplanParsing.needsCn1(ExtractionMode.YELLOW))
        assertFalse(EplanParsing.needsFunction(ExtractionMode.DEVICE))
        assertFalse(EplanParsing.needsCn1(ExtractionMode.FIXTURE))
        assertTrue(EplanParsing.needsLayout(ExtractionMode.QUANTITY))
        assertTrue(EplanParsing.needsFunction(ExtractionMode.QUANTITY))
        assertTrue(EplanParsing.needsCn1(ExtractionMode.QUANTITY))
    }

    @Test fun cabinetFiltersAcceptNonNumericNamesAndExclusionsWin() {
        val rules = RuleSettings(cabinetFilter = "主柜，控制柜A C11-1", excludedCabinets = "控制柜A")
        assertTrue(EplanParsing.acceptsCabinet("主柜", rules))
        assertTrue(EplanParsing.acceptsCabinet("C11-1", rules))
        assertFalse(EplanParsing.acceptsCabinet("控制柜A", rules))
        assertFalse(EplanParsing.acceptsCabinet("C12", rules))
    }

    @Test fun defaultsUseActualCategoryAndTrailingTaNumber() {
        assertEquals("电流互感器-L1", EplanParsing.defaultFunction("18TA04"))
        assertEquals("数字量输入", EplanParsing.defaultFunction("18DI02"))
        assertNull(EplanParsing.defaultFunction("18XS01"))
        assertEquals("18XS", EplanParsing.categoryPrefix("18XS01"))
        assertFalse(EplanParsing.isFunction("NXJ/2Z(D)_DC24"))
        assertTrue(EplanParsing.isFunction("1-8限位端子"))
        assertTrue(EplanParsing.isFunction("STO"))
    }

    private fun box(text: String, x: Float, y: Float) = PdfTextBox(text, x, y, 20f, 8f, fontSize = 8f)
}
