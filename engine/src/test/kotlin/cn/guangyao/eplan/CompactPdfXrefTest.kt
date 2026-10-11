package cn.guangyao.eplan

import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSObjectKey
import org.apache.pdfbox.pdfparser.XrefTrailerResolver.XRefType
import org.junit.Assert.*
import org.junit.Test

class CompactPdfXrefTest {
    @get:org.junit.Rule val testDirectory = org.junit.rules.TemporaryFolder()
    private val cacheDirectory: java.io.File get() = testDirectory.root

    @Test fun sparseBlocksDistinctGenerationsAndMutationsPreserveMapContract() {
        val map = CompactPdfXref()
        val plain = COSObjectKey(17, 0)
        val revision = COSObjectKey(17, 2)
        val sparse = COSObjectKey(9_000_000_001, 0)
        assertNull(map.put(plain, 700))
        map[revision] = 800
        map[sparse] = -49
        assertEquals(3, map.size)
        assertEquals(700L, map[plain])
        assertEquals(800L, map[revision])
        assertEquals(-49L, map[sparse])
        assertEquals(2 * 4096 * 8L, map.storageBytes())
        assertEquals(mapOf(plain to 700L, revision to 800L, sparse to -49L), map.toMap())
        assertEquals(700L, map.put(plain, 900))
        assertEquals(900L, map.remove(plain))
        assertNull(map[plain])
        assertEquals(2, map.size)
        map.clear()
        assertTrue(map.isEmpty())
        assertEquals(0L, map.storageBytes())
    }

    @Test fun prefetchedGenerationDoesNotChangeIteratorRemovalTarget() {
        val map = CompactPdfXref()
        val first = COSObjectKey(11, 1)
        val second = COSObjectKey(12, 2)
        map[first] = 101
        map[second] = 102
        val iterator = map.entries.iterator()
        val removed = iterator.next().key
        assertTrue(iterator.hasNext())
        iterator.remove()
        assertNull(map[removed])
        assertEquals(1, map.size)
        assertTrue(map.containsKey(iterator.next().key))
    }

    @Test fun incrementalTrailerChainAndHybridFirstEntryHaveSamePrecedenceAsPdfbox() {
        val reference = org.apache.pdfbox.pdfparser.XrefTrailerResolver()
        val compact = CompactPdfXref.Resolver()
        for (resolver in listOf(reference, compact)) {
            // Read newest first, as COSParser does. A stray unlinked section must stay excluded.
            resolver.nextXrefObj(300, XRefType.STREAM)
            resolver.setTrailer(COSDictionary().apply { setLong(COSName.PREV, 100) })
            resolver.setXRef(COSObjectKey(1, 0), 301)
            resolver.setXRef(COSObjectKey(2, 0), -500)
            resolver.setXRef(COSObjectKey(2, 0), -999) // obsolete hybrid duplicate
            resolver.setXRef(COSObjectKey(7, 1), 371)
            resolver.nextXrefObj(200, XRefType.TABLE)
            resolver.setTrailer(COSDictionary())
            resolver.setXRef(COSObjectKey(6, 0), 600)
            resolver.nextXrefObj(100, XRefType.TABLE)
            resolver.setTrailer(COSDictionary())
            resolver.setXRef(COSObjectKey(1, 0), 101)
            resolver.setXRef(COSObjectKey(3, 0), -500)
            resolver.setXRef(COSObjectKey(7, 0), 170)
            resolver.setStartxref(300)
        }
        assertEquals(reference.xrefTable, compact.xrefTable)
        assertEquals(setOf(2L, 3L), compact.getContainedObjectNumbers(500))
        assertFalse(compact.xrefTable.containsKey(COSObjectKey(6, 0)))
        assertEquals(301L, compact.xrefTable[COSObjectKey(1, 0)])
        assertEquals(XRefType.STREAM, compact.xrefType)
    }

    @Test fun absentStartxrefUsesFileOrderLikePdfbox() {
        val reference = org.apache.pdfbox.pdfparser.XrefTrailerResolver()
        val compact = CompactPdfXref.Resolver()
        for (resolver in listOf(reference, compact)) {
            for (offset in listOf(300L, 100L, 200L)) {
                resolver.nextXrefObj(offset, XRefType.TABLE)
                resolver.setTrailer(COSDictionary())
                resolver.setXRef(COSObjectKey(8, 0), offset)
            }
            resolver.setStartxref(-1)
        }
        assertEquals(reference.xrefTable, compact.xrefTable)
        assertEquals(300L, compact.xrefTable[COSObjectKey(8, 0)])
    }
}
