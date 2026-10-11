package cn.guangyao.eplan;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.io.ScratchFile;
import org.apache.pdfbox.pdfparser.XrefTrailerResolver;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/** PDFBox's public Map contract without three boxed entries per PDF object. */
final class CompactPdfXref extends AbstractMap<COSObjectKey, Long> {
    private static final int SHIFT = 12;
    private static final int BLOCK_SIZE = 1 << SHIFT;
    private final Map<Long, long[]> blocks = new HashMap<>();
    // Nonzero generations are uncommon but remain distinct keys, including incremental revisions.
    private final Map<COSObjectKey, Long> generations = new HashMap<>();
    private int size;

    long storageBytes() { return (long) blocks.size() * BLOCK_SIZE * Long.BYTES; }

    @Override public int size() { return size; }
    @Override public Long get(Object value) {
        if (!(value instanceof COSObjectKey)) return null;
        COSObjectKey key = (COSObjectKey) value;
        if (key.getGeneration() != 0) return generations.get(key);
        long[] block = blocks.get(key.getNumber() >>> SHIFT);
        long offset = block == null ? 0 : block[(int) (key.getNumber() & (BLOCK_SIZE - 1))];
        return offset == 0 ? null : offset;
    }
    @Override public boolean containsKey(Object key) { return get(key) != null; }
    @Override public Long put(COSObjectKey key, Long value) {
        // Offset zero is not a live indirect object: xref readers omit free entries.
        if (value == null || value == 0) return remove(key);
        if (key.getGeneration() != 0) {
            Long old = generations.put(key, value);
            if (old == null) size++;
            return old;
        }
        long[] block = blocks.computeIfAbsent(key.getNumber() >>> SHIFT, ignored -> new long[BLOCK_SIZE]);
        int index = (int) (key.getNumber() & (BLOCK_SIZE - 1));
        long old = block[index];
        block[index] = value;
        if (old == 0) size++;
        return old == 0 ? null : old;
    }
    @Override public Long remove(Object value) {
        if (!(value instanceof COSObjectKey)) return null;
        COSObjectKey key = (COSObjectKey) value;
        if (key.getGeneration() != 0) {
            Long old = generations.remove(key);
            if (old != null) size--;
            return old;
        }
        long[] block = blocks.get(key.getNumber() >>> SHIFT);
        if (block == null) return null;
        int index = (int) (key.getNumber() & (BLOCK_SIZE - 1));
        long old = block[index];
        block[index] = 0;
        if (old != 0) size--;
        return old == 0 ? null : old;
    }
    @Override public void clear() { blocks.clear(); generations.clear(); size = 0; }
    @Override public Set<Entry<COSObjectKey, Long>> entrySet() {
        return new AbstractSet<Entry<COSObjectKey, Long>>() {
            @Override public int size() { return CompactPdfXref.this.size(); }
            @Override public Iterator<Entry<COSObjectKey, Long>> iterator() {
                return new Iterator<Entry<COSObjectKey, Long>>() {
                    final Iterator<Entry<Long, long[]>> chunks = blocks.entrySet().iterator();
                    // A tiny snapshot of uncommon generation keys permits Map iterator.remove()
                    // even after hasNext() has prefetched a later entry.
                    final Iterator<COSObjectKey> extra = new ArrayList<>(generations.keySet()).iterator();
                    Entry<Long, long[]> chunk;
                    int index;
                    Entry<COSObjectKey, Long> next;
                    COSObjectKey previous;
                    private void advance() {
                        while (next == null) {
                            if (chunk != null) {
                                while (index < BLOCK_SIZE) {
                                    int current = index++;
                                    long offset = chunk.getValue()[current];
                                    if (offset != 0) {
                                        next = new SimpleImmutableEntry<>(new COSObjectKey((chunk.getKey() << SHIFT) + current, 0), offset);
                                        return;
                                    }
                                }
                            }
                            if (chunks.hasNext()) { chunk = chunks.next(); index = 0; }
                            else {
                                while (extra.hasNext()) {
                                    COSObjectKey key = extra.next();
                                    Long value = generations.get(key);
                                    if (value != null) { next = new SimpleImmutableEntry<>(key, value); break; }
                                }
                                return;
                            }
                        }
                    }
                    @Override public boolean hasNext() { advance(); return next != null; }
                    @Override public Entry<COSObjectKey, Long> next() {
                        advance();
                        if (next == null) throw new NoSuchElementException();
                        Entry<COSObjectKey, Long> result = next;
                        previous = result.getKey(); next = null;
                        return result;
                    }
                    @Override public void remove() {
                        if (previous == null) throw new IllegalStateException();
                        CompactPdfXref.this.remove(previous);
                        previous = null;
                    }
                };
            }
        };
    }

    /** Share the resolver's index; never expand it into COSDocument's private HashMap. */
    static final class Document extends COSDocument {
        private Map<COSObjectKey, Long> index = new CompactPdfXref();
        Document(ScratchFile scratch) { super(scratch); }
        @Override public Map<COSObjectKey, Long> getXrefTable() { return index; }
        @Override public void addXRefTable(Map<COSObjectKey, Long> values) {
            if (index.isEmpty()) index = values;
            else if (index != values) index.putAll(values);
        }
    }

    /** Keep PDFBox's trailer handling, replacing only its boxed xref tables. */
    static final class Resolver extends XrefTrailerResolver {
        private static final class Section {
            CompactPdfXref index = new CompactPdfXref();
            COSDictionary trailer;
        }
        private final Map<Long, Section> sections = new HashMap<>();
        private Section current;
        private CompactPdfXref resolved;
        @Override public void nextXrefObj(long offset, XRefType type) {
            super.nextXrefObj(offset, type);
            current = new Section(); sections.put(offset, current);
        }
        @Override public void setXRef(COSObjectKey key, long offset) {
            // First entry wins within one section, including obsolete /XRefStm hybrid entries.
            if (current != null && !current.index.containsKey(key)) current.index.put(key, offset);
        }
        @Override public void setTrailer(COSDictionary trailer) {
            super.setTrailer(trailer);
            if (current != null) current.trailer = trailer;
        }
        @Override public void setStartxref(long start) {
            if (resolved != null) return;
            super.setStartxref(start);
            List<Long> chain = new ArrayList<>();
            if (!sections.containsKey(start)) {
                chain.addAll(sections.keySet()); Collections.sort(chain);
            } else {
                Set<Long> visited = new HashSet<>();
                long offset = start;
                while (sections.containsKey(offset) && visited.add(offset)) {
                    chain.add(offset);
                    COSDictionary trailer = sections.get(offset).trailer;
                    if (trailer == null) break;
                    offset = trailer.getLong(COSName.PREV, -1L);
                }
                Collections.reverse(chain);
            }
            resolved = new CompactPdfXref();
            for (Long offset : chain) {
                CompactPdfXref index = sections.get(offset).index;
                if (resolved.isEmpty()) resolved = index;
                else resolved.putAll(index);
            }
            // Release obsolete sections, and share the final primitive arrays with the document.
            sections.clear(); current = null;
        }
        @Override public Map<COSObjectKey, Long> getXrefTable() { return resolved; }
        @Override public Set<Long> getContainedObjectNumbers(int stream) {
            if (resolved == null) return null;
            Set<Long> result = new HashSet<>();
            for (Entry<COSObjectKey, Long> item : resolved.entrySet()) {
                if (item.getValue() == -(long) stream) result.add(item.getKey().getNumber());
            }
            return result;
        }
        @Override protected void reset() {
            super.reset(); sections.clear(); current = null; resolved = null;
        }
    }
}
