package cn.guangyao.eplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelEditsTest {
    private var serial = 0

    private fun row(column: LabelColumn, layout: String, text: String, kind: RecordKind = RecordKind.LABEL,
        capacity: Int? = null) = LabelRecord("r-${serial++}", column, layout, text, kind = kind, cn1Capacity = capacity)

    private fun group(column: LabelColumn, layout: String, vararg labels: String) = listOf(
        row(column, layout, layout, RecordKind.GROUP), row(column, layout, "印度预热压预热压", RecordKind.FILE_LABEL),
    ) + labels.map { row(column, layout, it) }

    private fun cn1(layout: String = "C01", um: String = "UM01", count: Int = 2, spares: Int = 4, capacity: Int? = 6) =
        group(LabelColumn.CN1, layout) + row(LabelColumn.CN1, layout, um, RecordKind.UM_HEADER, capacity) +
            List(count) { row(LabelColumn.CN1, layout, "$um:CN1:${it + 1}/XT01:${it + 1}") } +
            List(spares) { row(LabelColumn.CN1, layout, "备用", RecordKind.SPARE) }

    private fun result(records: List<LabelRecord>, mode: ExtractionMode = ExtractionMode.FULL,
        completed: Boolean = true, addSpares: Boolean = true) = XlsxExporter.recomputeQuantities(ExtractionResult(
        "result", "doc", "印度预热压.pdf", mode, 1L, records, emptyList(), 4023, completed,
        RuleSettings(addSpares = addSpares),
    ))

    private fun labels(result: ExtractionResult, column: LabelColumn) = result.records.filter { it.column == column && it.kind == RecordKind.LABEL }
    private fun spareCount(result: ExtractionResult, layout: String = "C01") = result.records.count {
        it.column == LabelColumn.CN1 && it.layout == layout && it.kind == RecordKind.SPARE
    }

    @Test fun addingCn1UsesExistingCapacityAndKeepsRealWiresBeforeSpares() {
        val original = result(cn1())
        val changed = LabelEdits.add(original, LabelColumn.CN1, "C01", "UM01:CN1:3/XT01:3")
        assertEquals(3, labels(changed, LabelColumn.CN1).size)
        assertEquals(3, spareCount(changed))
        val d = changed.records.filter { it.column == LabelColumn.CN1 }
        assertTrue(d.indexOfLast { it.kind == RecordKind.LABEL } < d.indexOfFirst { it.kind == RecordKind.SPARE })
        assertEquals("C01至本列末尾:1-9", labels(changed, LabelColumn.QUANTITY).single().text)
        assertEquals(2, labels(original, LabelColumn.CN1).size)
    }

    @Test fun removingWireReleasesCapacityWithoutChangingQuantityRange() {
        val original = result(cn1(capacity = 8, spares = 6))
        val changed = LabelEdits.remove(original, labels(original, LabelColumn.CN1).first().id)
        assertEquals(1, labels(changed, LabelColumn.CN1).size)
        assertEquals(7, spareCount(changed))
        assertEquals("C01至本列末尾:1-11", labels(changed, LabelColumn.QUANTITY).single().text)
    }

    @Test fun removingOverflowDoesNotInventCapacityButLaterReleaseDoesAddSpare() {
        var changed = result(cn1(count = 7, spares = 0, capacity = 6))
        changed = LabelEdits.remove(changed, labels(changed, LabelColumn.CN1).first().id)
        assertEquals(0, spareCount(changed))
        assertEquals(6, labels(changed, LabelColumn.CN1).size)
        changed = LabelEdits.remove(changed, labels(changed, LabelColumn.CN1).first().id)
        assertEquals(1, spareCount(changed))
        assertEquals(5, labels(changed, LabelColumn.CN1).size)
    }

    @Test fun unknownCapacityDoesNotGenerateSparesAndProducesAnIssue() {
        val original = result(cn1(count = 2, spares = 0, capacity = null))
        val changed = LabelEdits.remove(original, labels(original, LabelColumn.CN1).first().id)
        assertEquals(0, spareCount(changed))
        assertTrue(changed.issues.any { it.message.contains("容量尚未确认") })
    }

    @Test fun legacySpareEvidenceCanPreserveOnlyItsOwnUmCapacity() {
        val original = result(cn1(count = 2, spares = 2, capacity = null) + cn1("C02", "UM02", 1, 9, 10))
        val changed = LabelEdits.remove(original, labels(original, LabelColumn.CN1).first().id)
        assertEquals(3, spareCount(changed, "C01"))
        assertEquals(9, spareCount(changed, "C02"))
        assertEquals(4, changed.records.single { it.layout == "C01" && it.kind == RecordKind.UM_HEADER }.cn1Capacity)
    }

    @Test fun lastWireDeletesItsUmAndCabinetEvenWithoutSpareGeneration() {
        listOf(true, false).forEach { completed ->
            val original = result(cn1(count = 1, spares = 0), completed = completed, addSpares = false)
            val changed = LabelEdits.remove(original, labels(original, LabelColumn.CN1).single().id)
            assertFalse(changed.records.any { it.column == LabelColumn.CN1 })
            assertFalse(labels(changed, LabelColumn.QUANTITY).any { it.layout == "C01" })
        }
    }

    @Test fun deletingOneUmRetainsItsSiblingAndOtherCabinets() {
        val first = cn1(count = 1, spares = 5)
        val sibling = cn1(um = "UM02", count = 2, spares = 4).drop(2)
        val other = cn1("C02", "UM03", 1, 5)
        val original = result(first + sibling + group(LabelColumn.DEVICE, "C09", "XT90") + other)
        val changed = LabelEdits.remove(original, first.first { it.kind == RecordKind.LABEL }.id)
        assertEquals(listOf("UM02", "UM03"), changed.records.filter { it.kind == RecordKind.UM_HEADER }.map { it.text })
        assertEquals(4, spareCount(changed, "C01"))
        assertEquals(5, spareCount(changed, "C02"))
        assertEquals("XT90", labels(changed, LabelColumn.DEVICE).single().text)
    }

    @Test fun addingNewCombinedUmGetsOneHeaderAndPerAxisCapacity() {
        val original = result(cn1())
        val changed = LabelEdits.add(original, LabelColumn.CN1, "C01", "UM11/12:CN1:1/XT01:1")
        val header = changed.records.single { it.kind == RecordKind.UM_HEADER && it.text == "UM11/12" }
        assertEquals(12, header.cn1Capacity)
        assertEquals(15, spareCount(changed)) // 4 existing + 11 in the new two-axis group.
        assertEquals(listOf("UM01", "UM11/12"), changed.records.filter { it.kind == RecordKind.UM_HEADER }.map { it.text })
    }

    @Test fun incompleteResultsNeverPadNewGroupsWithSpares() {
        val original = result(cn1(spares = 0, capacity = null), completed = false)
        val changed = LabelEdits.add(original, LabelColumn.CN1, "C01", "UM02:CN1:1/XT02:1")
        assertEquals(0, spareCount(changed))
        assertEquals(null, changed.records.single { it.kind == RecordKind.UM_HEADER && it.text == "UM02" }.cn1Capacity)
    }

    @Test fun cn1EditsKeepEndpointOrderAndEvidenceButCannotMoveUm() {
        val original = result(cn1())
        val target = labels(original, LabelColumn.CN1).first().copy(page = 259, x = 10f, favorite = true)
        val prepared = original.copy(records = original.records.map { if (it.id == target.id) target else it })
        val changed = LabelEdits.change(prepared, target.id, "XT01:7/-UM01:CN1:7")
        val edited = changed.records.single { it.id == target.id }
        assertEquals("XT01:7/-UM01:CN1:7", edited.text)
        assertEquals(target.sourceText, edited.sourceText)
        assertEquals(259, edited.page)
        assertTrue(edited.favorite && edited.edited)
        assertThrows(IllegalArgumentException::class.java) { LabelEdits.change(prepared, target.id, "UM02:CN1:7/XT01:7") }
    }

    @Test fun fixedSeatUniquenessIsCabinetLocalAndAlsoAppliesToEdits() {
        val original = result(group(LabelColumn.FIXTURE, "C01", "XT01", "XT02") + group(LabelColumn.FIXTURE, "C02", "XT01"))
        val target = labels(original, LabelColumn.FIXTURE).first { it.text == "XT02" }
        assertThrows(IllegalArgumentException::class.java) { LabelEdits.change(original, target.id, "xt01") }
        assertThrows(IllegalArgumentException::class.java) { LabelEdits.add(original, LabelColumn.FIXTURE, "C01", "XT01") }
        val changed = LabelEdits.add(original, LabelColumn.FIXTURE, "C02", "XT02")
        assertEquals(4, labels(changed, LabelColumn.FIXTURE).size)
    }

    @Test fun deviceDuplicatesRemainDistinctAndQuantitiesRecalculate() {
        val original = result(group(LabelColumn.DEVICE, "C01", "XT01", "XT01"))
        val changed = LabelEdits.add(original, LabelColumn.DEVICE, "C01", "XT01")
        assertEquals(listOf("XT01", "XT01", "XT01"), labels(changed, LabelColumn.DEVICE).map { it.text })
        assertEquals(3, labels(changed, LabelColumn.DEVICE).map { it.id }.distinct().size)
        assertEquals("C01至本列末尾:1-5", labels(changed, LabelColumn.QUANTITY).single().text)
        val removed = LabelEdits.remove(changed, labels(changed, LabelColumn.DEVICE).last().id)
        assertEquals("C01至本列末尾:1-4", labels(removed, LabelColumn.QUANTITY).single().text)
    }

    @Test fun headersSparesComputedQuantitiesAndHiddenDependenciesAreProtected() {
        val original = result(cn1() + group(LabelColumn.DEVICE, "C01", "XT01"))
        original.records.filter { it.kind != RecordKind.LABEL || it.column == LabelColumn.QUANTITY }.forEach { record ->
            assertThrows(IllegalArgumentException::class.java) { LabelEdits.remove(original, record.id) }
            assertThrows(IllegalArgumentException::class.java) { LabelEdits.change(original, record.id, "XT09") }
        }
        val onlyQuantities = original.copy(mode = ExtractionMode.QUANTITY)
        val hidden = labels(original, LabelColumn.DEVICE).single()
        assertThrows(IllegalArgumentException::class.java) { LabelEdits.remove(onlyQuantities, hidden.id) }
        assertThrows(IllegalArgumentException::class.java) { LabelEdits.change(onlyQuantities, hidden.id, "XT09") }
        assertThrows(IllegalArgumentException::class.java) { LabelEdits.add(onlyQuantities, LabelColumn.DEVICE, "C01", "XT09") }
    }

    @Test fun validationRejectsIncompleteWiresHiddenLeadersAndUnknownCabinets() {
        val original = result(group(LabelColumn.DEVICE, "C01", "XT01"))
        val target = labels(original, LabelColumn.DEVICE).single()
        listOf(" -XT01", "X T01", "", "XT01\nXT02").forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { LabelEdits.change(original, target.id, text) }
        }
        assertThrows(IllegalArgumentException::class.java) { LabelEdits.add(original, LabelColumn.CN1, "C01", "UM01:CN1:1") }
        assertThrows(IllegalArgumentException::class.java) { LabelEdits.add(original, LabelColumn.DEVICE, "不存在的柜体", "XT02") }
    }

    @Test fun manuallyAddedGroupsUseTheFilenameEvenWhenOtherColumnsHaveLegacyLabels() {
        val original = result(group(LabelColumn.DEVICE, "C01", "XT01")).copy(documentName = "B06B07-1.pdf")
        val changed = LabelEdits.add(original, LabelColumn.FIXTURE, "C01", "XT02")
        val label = changed.records.single { it.column == LabelColumn.FIXTURE && it.kind == RecordKind.FILE_LABEL }
        assertEquals("B06B07-1.pdf", label.text)
        assertEquals("B06B07-1.pdf", label.sourceText)
    }
}
