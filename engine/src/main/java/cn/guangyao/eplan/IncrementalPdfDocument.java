package cn.guangyao.eplan;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessBufferedFileInputStream;
import org.apache.pdfbox.io.ScratchFile;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.DefaultResourceCache;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

/**
 * A read-only text-extraction session. PDFBox 2's normal initialParse expands the complete catalog,
 * including EPLAN's enormous device outline, every image, and attachments. Disk scratch storage
 * alone does not bound that COS object graph. This parser resolves structural metadata once and
 * materializes only one page's text resources at a time. Never use its document to save/render a PDF.
 */
public final class IncrementalPdfDocument implements Closeable {
    private final PageParser parser;
    private final PDDocument pdf;
    private final File sourceFile;

    public IncrementalPdfDocument(File sourceFile, File cacheDirectory, BooleanSupplier cancelled) throws IOException {
        this(sourceFile, cacheDirectory, cancelled, 200_000);
    }

    /** Lower structural opening budget for optional preview probes; extraction keeps its original limit. */
    public IncrementalPdfDocument(File sourceFile, File cacheDirectory, BooleanSupplier cancelled, int maxStructuralNodes) throws IOException {
        if (maxStructuralNodes < 1 || maxStructuralNodes > 200_000) throw new IllegalArgumentException("Invalid structural node budget");
        this.sourceFile = sourceFile;
        RandomAccessBufferedFileInputStream source = new CancellableSource(sourceFile, cancelled);
        ScratchFile scratch = null;
        PageParser candidate = null;
        try {
            // A fixed input LRU (~4 MiB) and disk-only compressed/decompressed streams, not byte[].
            scratch = new ScratchFile(MemoryUsageSetting.setupTempFileOnly(1536L * 1024 * 1024).setTempDir(cacheDirectory));
            candidate = new PageParser(source, scratch, cancelled, maxStructuralNodes);
            candidate.parse();
            pdf = candidate.getPDDocument();
            parser = candidate;
            parser.releaseTransientObjects();
        } catch (Throwable failure) {
            // PDFBox's convenience loader only cleans up IOException, leaking scratch files on OOM.
            if (candidate != null) try { candidate.getDocument().close(); } catch (Throwable ignored) { }
            if (scratch != null) try { scratch.close(); } catch (Throwable ignored) { }
            try { source.close(); } catch (Throwable ignored) { }
            throw failure;
        }
    }

    public PDDocument getPdf() { return pdf; }

    public PDDocument preparePage(int oneBasedPage) throws IOException {
        parser.checkCancelled();
        parser.releaseTransientObjects();
        pdf.setResourceCache(new DefaultResourceCache());
        PDPage page = pdf.getPage(oneBasedPage - 1);
        parser.materializePage(page.getCOSObject());
        return pdf;
    }

    public void releasePage() throws IOException {
        pdf.setResourceCache(new DefaultResourceCache());
        parser.releaseTransientObjects();
    }

    /**
     * Open only the compressed bytes of a skipped, unencrypted image on the currently prepared
     * page. The caller validates the image format, decodes within its own pixel budget, and closes
     * the stream before releasePage(). Unsupported/encrypted/oversized images return null. This
     * never loads the image or the rest of the document into the PDFBox object graph.
     */
    public InputStream openImageStream(COSStream image) throws IOException {
        return parser.openImageStream(sourceFile, image);
    }

    /**
     * Optional navigation evidence for this page only. Does not load content, appearances, device
     * outlines, or target-page resources. Call releasePage() when finished, including on failure.
     * Returned values contain no PDF objects and remain usable after that release.
     */
    public List<LinkEvidence> readPageLinks(int oneBasedPage) throws IOException {
        parser.checkCancelled();
        if (oneBasedPage < 1 || oneBasedPage > pdf.getNumberOfPages()) {
            throw new IOException("PDF 链接来源页不存在。");
        }
        return parser.readPageLinks(pdf.getPage(oneBasedPage - 1));
    }

    /** Displayed PDF points, cropped and page-rotation adjusted, origin at the upper left. */
    public static final class Rect {
        public final float x, y, width, height;
        Rect(float x, float y, float width, float height) {
            this.x = x; this.y = y; this.width = width; this.height = height;
        }
    }

    public static final class LinkEvidence {
        public final Rect source;
        public final int targetPage;
        /** Null for page-only destinations or unsupported/malformed highlight scripts. */
        public final Rect target;
        LinkEvidence(Rect source, int targetPage, Rect target) {
            this.source = source; this.targetPage = targetPage; this.target = target;
        }
    }

    @Override public void close() throws IOException { pdf.close(); }

    /** Visible for a cloud regression that proves skipped device/image objects never materialize. */
    int retainedObjectCount() throws IOException {
        int count = 0;
        for (COSObject object : parser.getDocument().getObjects()) if (object.getObject() != null) count++;
        return count;
    }

    /** Primitive backing allocation, excluding the small sparse-generation fallback. */
    long xrefIndexBytes() throws IOException {
        Map<COSObjectKey, Long> index = parser.getDocument().getXrefTable();
        return index instanceof CompactPdfXref ? ((CompactPdfXref) index).storageBytes() : -1L;
    }

    static final class CancelledException extends IOException {
        CancelledException() { super("任务已取消"); }
    }

    private static final class CancellableSource extends RandomAccessBufferedFileInputStream {
        private final BooleanSupplier cancelled;
        private int reads;
        CancellableSource(File source, BooleanSupplier cancelled) throws IOException {
            super(source);
            this.cancelled = cancelled;
        }
        private void check() throws CancelledException {
            if (++reads % 1024 == 0 && cancelled.getAsBoolean()) throw new CancelledException();
        }
        @Override public int read() throws IOException { check(); return super.read(); }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            check(); return super.read(bytes, offset, length);
        }
    }

    private static final class PageParser extends PDFParser {
        private static final long MAX_REQUIRED_STREAM = 64L * 1024 * 1024;
        private static final int MAX_NODES = 200_000;
        private static final Pattern DEVICE_TREE = Pattern.compile("^(设备树|设备列表|Devices?\\b)", Pattern.CASE_INSENSITIVE);
        private final Set<COSObject> pinned = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<Long, Set<COSObject>> pinnedByObjectStream = new HashMap<>();
        // References only page dictionaries already pinned by the page-tree walk, never resources.
        private final Map<COSDictionary, Integer> pageNumbers = new IdentityHashMap<>();
        private final List<COSDictionary> pageDictionaries = new ArrayList<>();
        private final Map<COSStream, ImageRange> imageRanges = new IdentityHashMap<>();
        private final BooleanSupplier cancelled;
        private final int maxStructuralNodes;
        private int structuralNodes;

        PageParser(RandomAccessBufferedFileInputStream source, ScratchFile scratch, BooleanSupplier cancelled, int maxStructuralNodes) throws IOException {
            // PDFParser creates a COSDocument with a private boxed HashMap. Close its empty,
            // independent scratch owner before installing the compact-index document. Sharing the
            // real scratch with that abandoned object would let its finalizer close our live file.
            super(source, ScratchFile.getMainMemoryOnlyInstance());
            document.close();
            document = new CompactPdfXref.Document(scratch);
            xrefTrailerResolver = new CompactPdfXref.Resolver();
            this.cancelled = cancelled;
            this.maxStructuralNodes = maxStructuralNodes;
        }

        void checkCancelled() throws CancelledException {
            if (cancelled.getAsBoolean()) throw new CancelledException();
        }

        private static final class ImageRange {
            final long offset, length;
            ImageRange(long offset, long length) { this.offset = offset; this.length = length; }
        }

        InputStream openImageStream(File sourceFile, COSStream image) throws IOException {
            checkCancelled();
            ImageRange range = imageRanges.get(image);
            if (range == null || range.length < 0 || range.length > MAX_REQUIRED_STREAM) return null;
            FileInputStream input = new FileInputStream(sourceFile);
            try {
                long sourceLength = input.getChannel().size();
                if (range.offset < 0 || range.offset > sourceLength || range.length > sourceLength - range.offset) {
                    throw new IOException("PDF 图片流长度无效。");
                }
                input.getChannel().position(range.offset);
                checkCancelled();
            } catch (Throwable failure) {
                try { input.close(); } catch (Throwable ignored) { }
                throw failure;
            }
            return new InputStream() {
                private long remaining = range.length;
                @Override public int read() throws IOException {
                    checkCancelled();
                    if (remaining == 0) return -1;
                    int value = input.read();
                    if (value == -1) throw new EOFException("PDF 图片流被截断。");
                    remaining--;
                    return value;
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (bytes == null) throw new NullPointerException("bytes");
                    if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
                    checkCancelled();
                    if (length == 0) return 0;
                    if (remaining == 0) return -1;
                    int count = input.read(bytes, offset, (int) Math.min(remaining, length));
                    if (count == -1) throw new EOFException("PDF 图片流被截断。");
                    remaining -= count;
                    return count;
                }
                @Override public long skip(long count) throws IOException {
                    checkCancelled();
                    long skipped = input.skip(Math.max(0L, Math.min(remaining, count)));
                    remaining -= skipped;
                    return skipped;
                }
                @Override public int available() throws IOException {
                    return (int) Math.min(remaining, input.available());
                }
                @Override public void close() throws IOException { input.close(); }
            };
        }

        @Override protected void initialParse() throws IOException {
            COSDictionary trailer = retrieveTrailer();
            // Resolves trailer values individually (including encryption), never their complete graphs.
            COSBase rootBase = parseTrailerValuesDynamically(trailer);
            if (!(rootBase instanceof COSDictionary)) throw new IOException("PDF 缺少有效的文档目录。");
            COSDictionary root = (COSDictionary) rootBase;
            pinValue(trailer.getItem(COSName.ROOT));
            pinValue(trailer.getItem(COSName.ENCRYPT));
            root.setItem(COSName.TYPE, COSName.CATALOG);
            walkPageTree(root.getItem(COSName.PAGES), 0, identitySet());
            releaseTransientObjects();
            COSBase outlines = pinValue(root.getItem(COSName.OUTLINES));
            if (outlines instanceof COSDictionary) {
                walkOutline(((COSDictionary) outlines).getItem(COSName.FIRST), 0, identitySet());
            }
            // Both legacy /Dests and the /Names /Dests name tree are allowed; EmbeddedFiles stays lazy.
            materialize(root.getItem(COSName.DESTS), true, 0, identitySet());
            COSBase names = pinValue(root.getItem(COSName.NAMES));
            if (names instanceof COSDictionary) {
                materialize(((COSDictionary) names).getItem(COSName.DESTS), true, 0, identitySet());
            }
            document.setDecrypted();
            initialParseDone = true;
        }

        private static Set<COSBase> identitySet() {
            return Collections.newSetFromMap(new IdentityHashMap<>());
        }

        private void checkDepth(int depth) throws IOException {
            checkCancelled();
            if (depth > 80) throw new IOException("PDF 对象层级异常复杂，已安全停止；请修复或拆分此图纸。");
        }

        private COSBase resolve(COSBase value) throws IOException {
            checkCancelled();
            if (value instanceof COSObject) return parseObjectDynamically((COSObject) value, false);
            return value;
        }

        private COSBase pinValue(COSBase value) throws IOException {
            if (value instanceof COSObject && pinned.add((COSObject) value)) {
                COSObject object = (COSObject) value;
                Long offset = document.getXrefTable().get(new COSObjectKey(object));
                if (offset != null && offset < 0) {
                    pinnedByObjectStream.computeIfAbsent(offset, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(object);
                }
            }
            return resolve(value);
        }

        private int walkPageTree(COSBase value, int depth, Set<COSBase> seen) throws IOException {
            checkDepth(depth);
            COSBase base = pinValue(value);
            if (!(base instanceof COSDictionary) || !seen.add(base)) throw new IOException("PDF 页面树损坏或循环引用。");
            if (++structuralNodes > maxStructuralNodes) throw new IOException("PDF 页面目录过于复杂，已安全停止；请按项目拆分。");
            COSDictionary node = (COSDictionary) base;
            materialize(node.getItem(COSName.MEDIA_BOX), true, 0, identitySet());
            materialize(node.getItem(COSName.CROP_BOX), true, 0, identitySet());
            pinValue(node.getItem(COSName.ROTATE));
            COSBase children = pinValue(node.getItem(COSName.KIDS));
            if (children == null) {
                node.setItem(COSName.TYPE, COSName.PAGE);
                pageDictionaries.add(node);
                pageNumbers.put(node, pageDictionaries.size());
                if (pageDictionaries.size() % 128 == 0) releaseTransientObjects();
                return 1;
            }
            if (!(children instanceof COSArray)) throw new IOException("PDF 页面树没有有效的子页列表。");
            int count = 0;
            for (COSBase child : (COSArray) children) count += walkPageTree(child, depth + 1, seen);
            node.setItem(COSName.TYPE, COSName.PAGES);
            node.setInt(COSName.COUNT, count);
            return count;
        }

        private void walkOutline(COSBase value, int depth, Set<COSBase> seen) throws IOException {
            checkDepth(depth);
            while (value != null) {
                COSBase base = pinValue(value);
                if (!(base instanceof COSDictionary) || !seen.add(base)) return;
                if (++structuralNodes > maxStructuralNodes) throw new IOException("PDF 页面目录过于复杂，已安全停止；请按项目拆分。");
                COSDictionary node = (COSDictionary) base;
                pinValue(node.getItem(COSName.TITLE));
                String title = node.getString(COSName.TITLE, "").trim();
                if (!DEVICE_TREE.matcher(title).find()) {
                    materialize(node.getItem(COSName.DEST), true, 0, identitySet());
                    materialize(node.getItem(COSName.A), true, 0, identitySet());
                    walkOutline(node.getItem(COSName.FIRST), depth + 1, seen);
                }
                value = node.getItem(COSName.NEXT);
                // Compressed structural objects bring unrequested siblings along with them.
                // Evict those while opening long documents instead of waiting for page one.
                if (structuralNodes % 128 == 0) releaseTransientObjects();
            }
        }

        void materializePage(COSDictionary page) throws IOException {
            Set<COSBase> seen = identitySet();
            materialize(page.getItem(COSName.CONTENTS), false, 0, seen);
            Set<COSBase> parents = identitySet();
            COSDictionary ancestor = page;
            while (ancestor != null && parents.add(ancestor)) {
                // Resources can be inherited from any Pages ancestor. Resolve the nearest definition.
                COSBase resources = ancestor.getItem(COSName.RESOURCES);
                if (resources != null) { materialize(resources, false, 0, seen); break; }
                COSBase parent = resolve(ancestor.getItem(COSName.PARENT));
                ancestor = parent instanceof COSDictionary ? (COSDictionary) parent : null;
            }
        }

        private static final int MAX_LINKS = 10_000;
        private static final int MAX_LINK_OBJECTS = 160_000;
        private static final int MAX_SCRIPT_BYTES = 8192;
        private static final String NUMBER = "([-+]?(?:[0-9]{1,9}(?:\\.[0-9]{0,9})?|\\.[0-9]{1,9})(?:[eE][-+]?[0-9]{1,3})?)";
        // Deliberately the entire script, not a substring search: no expressions, extra calls,
        // assignments, properties, or executable interpretation are accepted.
        private static final Pattern HIGHLIGHT = Pattern.compile("\\s*highlight\\s*\\(\\s*([0-9]{1,9})\\s*,\\s*\\[\\s*"
                + NUMBER + "\\s*,\\s*" + NUMBER + "\\s*,\\s*" + NUMBER + "\\s*,\\s*" + NUMBER
                + "\\s*]\\s*\\)\\s*;?\\s*");

        private static final class LinkLimit extends IOException {
            LinkLimit() { super("单页 PDF 链接过于复杂，已跳过关联信息；文字仍可继续提取。"); }
        }

        private final class LinkBudget {
            int objects;
            COSBase read(COSBase value) throws IOException {
                checkCancelled();
                // A malformed chain of indirect references cannot turn into unbounded recursion.
                for (int depth = 0; depth < 16; depth++) {
                    if (++objects > MAX_LINK_OBJECTS) throw new LinkLimit();
                    if (!(value instanceof COSObject)) return value;
                    COSBase next = resolve(value);
                    if (next == value) return null;
                    value = next;
                }
                return null;
            }
        }

        List<LinkEvidence> readPageLinks(PDPage page) throws IOException {
            LinkBudget budget = new LinkBudget();
            COSBase value = budget.read(page.getCOSObject().getItem(COSName.ANNOTS));
            if (!(value instanceof COSArray)) return Collections.emptyList();
            COSArray annotations = (COSArray) value;
            if (annotations.size() > MAX_LINKS) throw new LinkLimit();
            List<LinkEvidence> result = new ArrayList<>();
            for (COSBase entry : annotations) {
                checkCancelled();
                try {
                    COSBase base = budget.read(entry);
                    if (!(base instanceof COSDictionary)) continue;
                    COSDictionary annotation = (COSDictionary) base;
                    if (!COSName.getPDFName("Link").equals(budget.read(annotation.getItem(COSName.SUBTYPE)))) continue;
                    float[] rawRect = readRect(annotation.getItem(COSName.RECT), budget);
                    Rect source = rawRect == null ? null : displayRect(page, rawRect);
                    if (source == null) continue;
                    LinkTarget target = destination(annotation.getItem(COSName.DEST), budget, 0);
                    COSBase actionBase = budget.read(annotation.getItem(COSName.A));
                    if (actionBase instanceof COSDictionary) {
                        COSDictionary action = (COSDictionary) actionBase;
                        // Remote GoTo, URI, Launch, and additional actions are never followed.
                        if (COSName.getPDFName("GoTo").equals(budget.read(action.getItem(COSName.S)))) {
                            LinkTarget actionTarget = destination(action.getItem(COSName.D), budget, 0);
                            if (actionTarget != null) {
                                target = actionTarget;
                                findHighlight(action.getItem(COSName.NEXT), target, budget, 0, identitySet());
                            }
                        }
                    }
                    if (target != null) result.add(new LinkEvidence(source, target.page, target.rect));
                } catch (CancelledException | LinkLimit failure) {
                    throw failure;
                } catch (IOException | IllegalArgumentException ignored) {
                    // Navigation is supplementary. One broken link must not invalidate native text.
                }
            }
            return result;
        }

        private static final class LinkTarget {
            final int page;
            Rect rect;
            LinkTarget(int page, Rect rect) { this.page = page; this.rect = rect; }
        }

        private LinkTarget destination(COSBase value, LinkBudget budget, int depth) throws IOException {
            if (depth > 8) return null;
            COSBase base = budget.read(value);
            if (base instanceof COSDictionary) {
                return destination(((COSDictionary) base).getItem(COSName.D), budget, depth + 1);
            }
            if (!(base instanceof COSArray)) return null;
            COSArray array = (COSArray) base;
            if (array.size() < 2 || array.size() > 8) return null;
            COSBase page = budget.read(array.get(0));
            Integer number = page instanceof COSDictionary ? pageNumbers.get(page) : null;
            if (page instanceof COSNumber) {
                long candidate = ((COSNumber) page).longValue() + 1;
                if (candidate > 0 && candidate <= pageDictionaries.size()) number = (int) candidate;
            }
            if (number == null) return null;
            Rect rect = null;
            // FitR defines an actual rectangle. XYZ/FitH/FitV provide only a viewing point/line;
            // those intentionally remain page-only rather than inventing device boundaries.
            if (COSName.getPDFName("FitR").equals(budget.read(array.get(1))) && array.size() == 6) {
                float[] coordinates = numbers(array, 2, budget);
                if (coordinates != null) rect = displayRect(new PDPage(pageDictionaries.get(number - 1)), coordinates);
            }
            return new LinkTarget(number, rect);
        }

        private void findHighlight(COSBase value, LinkTarget target, LinkBudget budget, int depth, Set<COSBase> seen) throws IOException {
            if (value == null || depth > 12 || seen.size() >= 64 || target.rect != null) return;
            COSBase base = budget.read(value);
            if (base == null || !seen.add(base)) return;
            if (base instanceof COSArray) {
                COSArray array = (COSArray) base;
                if (array.size() > 64) return;
                for (COSBase item : array) findHighlight(item, target, budget, depth + 1, seen);
            } else if (base instanceof COSDictionary) {
                COSDictionary action = (COSDictionary) base;
                COSBase type = budget.read(action.getItem(COSName.S));
                if (!COSName.getPDFName("JavaScript").equals(type)) return;
                String script = readScript(action.getItem(COSName.getPDFName("JS")), budget);
                if (script != null) {
                    Matcher match = HIGHLIGHT.matcher(script);
                    if (match.matches() && Long.parseLong(match.group(1)) + 1 == target.page) {
                        float[] rect = new float[4];
                        boolean valid = true;
                        for (int i = 0; i < 4; i++) {
                            rect[i] = Float.parseFloat(match.group(i + 2));
                            valid &= finiteCoordinate(rect[i]);
                        }
                        if (valid) target.rect = displayHighlightRect(new PDPage(pageDictionaries.get(target.page - 1)), rect);
                    }
                }
                findHighlight(action.getItem(COSName.NEXT), target, budget, depth + 1, seen);
            }
        }

        private String readScript(COSBase value, LinkBudget budget) throws IOException {
            COSBase base = budget.read(value);
            if (base instanceof COSString) {
                COSString text = (COSString) base;
                return text.getBytes().length <= MAX_SCRIPT_BYTES ? text.getString() : null;
            }
            if (!(base instanceof COSStream)) return null;
            COSStream stream = (COSStream) base;
            if (stream.getLength() > MAX_SCRIPT_BYTES) return null;
            COSBase filter = budget.read(stream.getItem(COSName.FILTER));
            if (filter instanceof COSArray && ((COSArray) filter).size() == 1) filter = budget.read(((COSArray) filter).get(0));
            // JS metadata only needs unfiltered or simple Flate streams. Decode ourselves with an
            // output cap, since COSStream.createInputStream may inflate a whole stream to scratch.
            boolean flate = COSName.FLATE_DECODE.equals(filter) || COSName.getPDFName("Fl").equals(filter);
            if (filter != null && !flate) return null;
            if (budget.read(stream.getItem(COSName.DECODE_PARMS)) != null) return null;
            byte[] compressed;
            try (InputStream input = stream.createRawInputStream()) { compressed = readBounded(input); }
            if (compressed == null) return null;
            byte[] decoded = compressed;
            if (flate) try (InputStream input = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
                decoded = readBounded(input);
            }
            if (decoded == null) return null;
            if (decoded.length >= 2 && (decoded[0] & 255) == 254 && (decoded[1] & 255) == 255) {
                return new String(decoded, 2, decoded.length - 2, StandardCharsets.UTF_16BE);
            }
            return new String(decoded, StandardCharsets.ISO_8859_1);
        }

        private byte[] readBounded(InputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int count;
            while ((count = input.read(chunk)) != -1) {
                checkCancelled();
                if (output.size() + count > MAX_SCRIPT_BYTES) return null;
                output.write(chunk, 0, count);
            }
            return output.toByteArray();
        }

        private float[] readRect(COSBase value, LinkBudget budget) throws IOException {
            COSBase base = budget.read(value);
            return base instanceof COSArray && ((COSArray) base).size() == 4 ? numbers((COSArray) base, 0, budget) : null;
        }

        private float[] numbers(COSArray array, int offset, LinkBudget budget) throws IOException {
            float[] result = new float[4];
            for (int i = 0; i < 4; i++) {
                COSBase value = budget.read(array.get(i + offset));
                if (!(value instanceof COSNumber)) return null;
                result[i] = ((COSNumber) value).floatValue();
                if (!finiteCoordinate(result[i])) return null;
            }
            return result;
        }

        private static boolean finiteCoordinate(float value) { return Float.isFinite(value) && Math.abs(value) <= 10_000_000f; }

        private static Rect displayHighlightRect(PDPage page, float[] rect) {
            // EPLAN passes highlight coordinates directly to Acrobat addField, which uses
            // rotated user space (visible crop, lower-left origin). /Rect and /FitR instead
            // use default PDF user space. Do not apply rotation/crop displacement twice.
            // https://opensource.adobe.com/dc-acrobat-sdk-docs/library/jsapiref/doc.html#addfield
            PDRectangle crop = page.getCropBox();
            boolean rotated = Math.floorMod(page.getRotation(), 180) != 0;
            float width = rotated ? crop.getHeight() : crop.getWidth();
            float height = rotated ? crop.getWidth() : crop.getHeight();
            float left = Math.max(0f, Math.min(rect[0], rect[2]));
            float right = Math.min(width, Math.max(rect[0], rect[2]));
            float bottom = Math.max(0f, Math.min(rect[1], rect[3]));
            float top = Math.min(height, Math.max(rect[1], rect[3]));
            if (right <= left || top <= bottom) return null;
            return new Rect(left, height - top, right - left, top - bottom);
        }

        private static Rect displayRect(PDPage page, float[] rect) {
            PDRectangle crop = page.getCropBox();
            float left = Math.max(Math.min(rect[0], rect[2]), crop.getLowerLeftX()) - crop.getLowerLeftX();
            float right = Math.min(Math.max(rect[0], rect[2]), crop.getUpperRightX()) - crop.getLowerLeftX();
            float bottom = Math.max(Math.min(rect[1], rect[3]), crop.getLowerLeftY()) - crop.getLowerLeftY();
            float top = Math.min(Math.max(rect[1], rect[3]), crop.getUpperRightY()) - crop.getLowerLeftY();
            float width = right - left, height = top - bottom;
            if (!finiteCoordinate(left) || !finiteCoordinate(right) || !finiteCoordinate(top) || !finiteCoordinate(bottom)
                    || width <= 0f || height <= 0f) return null;
            switch ((page.getRotation() % 360 + 360) % 360) {
                case 90: return new Rect(bottom, left, height, width);
                case 180: return new Rect(crop.getWidth() - right, bottom, width, height);
                case 270: return new Rect(crop.getHeight() - top, crop.getWidth() - right, height, width);
                default: return new Rect(left, crop.getHeight() - top, width, height);
            }
        }

        private void materialize(COSBase value, boolean keep, int depth, Set<COSBase> seen) throws IOException {
            checkDepth(depth);
            COSBase base = keep ? pinValue(value) : resolve(value);
            if (base == null || !seen.add(base)) return;
            if (seen.size() > MAX_NODES) throw new IOException("单页 PDF 资源过于复杂，已安全停止；请简化或拆分图纸。");
            if (base instanceof COSDictionary) {
                COSDictionary dict = (COSDictionary) base;
                // A destination may refer back to a page; its content is deliberately left for preparePage.
                COSName type = dict.getCOSName(COSName.TYPE);
                if (COSName.PAGE.equals(type) || COSName.PAGES.equals(type)) return;
                if (COSName.IMAGE.equals(dict.getCOSName(COSName.SUBTYPE))) return;
                for (Map.Entry<COSName, COSBase> item : dict.entrySet()) {
                    COSName key = item.getKey();
                    // Parent links and non-text metadata must not turn one page into the whole catalog.
                    if (COSName.PARENT.equals(key) || COSName.P.equals(key) || COSName.METADATA.equals(key)
                            || COSName.ANNOTS.equals(key) || COSName.PIECE_INFO.equals(key)) continue;
                    materialize(item.getValue(), keep, depth + 1, seen);
                }
            } else if (base instanceof COSArray) {
                for (COSBase item : (COSArray) base) materialize(item, keep, depth + 1, seen);
            }
        }

        @Override protected COSBase parseObjectDynamically(long number, int generation, boolean requireUncompressed) throws IOException {
            checkCancelled();
            COSObjectKey key = new COSObjectKey(number, generation);
            Long offset = document.getXrefTable().get(key);
            if (offset != null && offset < 0 && document.getObjectFromPool(key).getObject() == null) {
                // Re-reading one member reparses the whole compressed object stream. PDFBox then
                // overwrites sibling COSObject values, including page-tree dictionaries already in use.
                // Keep those structural identities stable while allowing page resources to be reloaded.
                Map<COSObject, COSBase> saved = new IdentityHashMap<>();
                for (COSObject object : pinnedByObjectStream.getOrDefault(offset, Collections.emptySet())) {
                    if (object.getObject() != null) saved.put(object, object.getObject());
                }
                try {
                    return super.parseObjectDynamically(number, generation, requireUncompressed);
                } finally {
                    for (Map.Entry<COSObject, COSBase> entry : saved.entrySet()) entry.getKey().setObject(entry.getValue());
                }
            }
            return super.parseObjectDynamically(number, generation, requireUncompressed);
        }

        @Override protected COSStream parseCOSStream(COSDictionary dictionary) throws IOException {
            checkCancelled();
            long beforeLength = source.getPosition();
            COSBase lengthValue = resolve(dictionary.getItem(COSName.LENGTH));
            source.seek(beforeLength);
            long length = lengthValue instanceof COSNumber ? ((COSNumber) lengthValue).longValue() : -1L;
            if (securityHandler == null && COSName.IMAGE.equals(dictionary.getCOSName(COSName.SUBTYPE)) && length >= 0L) {
                // Text extraction never reads pixels. Seek past the actual image bytes without copying
                // them to scratch. Keep the image dictionary so PDFBox recognizes and ignores /Do.
                readString(); // stream
                // PDF stream EOL is CRLF or LF, not arbitrary whitespace (binary data may start with it).
                int first = source.read();
                if (first == '\r') { if (source.peek() == '\n') source.read(); }
                else if (first != '\n') { source.seek(beforeLength); return super.parseCOSStream(dictionary); }
                long imageOffset = source.getPosition();
                long end = imageOffset + length;
                if (end < source.getPosition() || end > source.length()) throw new IOException("PDF 图片流长度无效。");
                source.seek(end);
                skipSpaces();
                if (!"endstream".equals(readString())) throw new IOException("PDF 图片流损坏，无法安全跳过。");
                COSStream image = document.createCOSStream(dictionary);
                if (length <= MAX_REQUIRED_STREAM) imageRanges.put(image, new ImageRange(imageOffset, length));
                return image;
            }
            if (length > MAX_REQUIRED_STREAM) throw new IOException("PDF 单个文字或字体资源超过 64 MB，已安全停止；请优化 PDF 后重试。");
            return super.parseCOSStream(dictionary);
        }

        void releaseTransientObjects() throws IOException {
            imageRanges.clear();
            // Includes sibling objects implicitly decoded from compressed object streams.
            for (COSObject object : document.getObjects()) {
                if (!pinned.contains(object)) {
                    if (object.getObject() instanceof COSStream) ((COSStream) object.getObject()).close();
                    object.setObject(null);
                }
            }
        }
    }
}
