package meridian.launcher.update.wharf;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.brotli.dec.BrotliInputStream;

/**
 * The itch.io <b>wharf</b> binary-patch format ({@code .pwr}) and its content signature
 * ({@code .pwr.sig}) — the exact shape Hytale ships game deltas in. This class is the format
 * layer: magic/header framing, the compressed message stream, and a hand-rolled reader for the
 * handful of protobuf messages involved (no protobuf-java dependency). {@link WharfPatcher}
 * builds on it to apply a patch.
 *
 * <p>Layout, validated byte-for-byte against a real Hytale {@code 65→66.pwr} sample:
 * <pre>
 *   [magic i32 LE]           patch 0x0FEF5F00 · signature 0x0FEF5F01
 *   [frame] Header           {compression{algorithm, quality}}   (uncompressed)
 *   …compressed body (brotli, or raw when algorithm=NONE)…
 *     .pwr : SOURCE Container, TARGET Container, then per TARGET file a SyncHeader followed by
 *            SyncOps until HEY_YOU_DID_IT. BLOCK_RANGE copies from the SOURCE file it names;
 *            DATA writes literal bytes. Block size follows the file being rebuilt (TARGET), so
 *            the final block is partial.
 *     .sig : the newest build's Container, then a BlockHash per 64 KiB block of every file.
 * </pre>
 * Every message on the body stream is length-framed: {@code varint(len) + bytes}.
 */
public final class Wharf {

    public static final int PATCH_MAGIC = 0x0FEF5F00;
    public static final int SIGNATURE_MAGIC = 0x0FEF5F01;
    /** wharf block size: 64 KiB. */
    public static final long BLOCK = 65_536;

    // CompressionAlgorithm
    static final int COMP_NONE = 0;
    static final int COMP_BROTLI = 1;

    // SyncOp.Type
    public static final int OP_BLOCK_RANGE = 0;
    public static final int OP_DATA = 1;
    public static final int OP_HEY_YOU_DID_IT = 2049;

    private Wharf() {
    }

    // --- container model --------------------------------------------------------------

    public record FileEntry(String path, long mode, long size, long offset) {}
    public record DirEntry(String path, long mode) {}
    public record SymlinkEntry(String path, long mode, String dest) {}

    public static final class Container {
        public final List<FileEntry> files = new ArrayList<>();
        public final List<DirEntry> dirs = new ArrayList<>();
        public final List<SymlinkEntry> symlinks = new ArrayList<>();
        public long size;
    }

    public record SyncHeader(int type, long fileIndex) {}
    public record SyncOp(int type, int fileIndex, long blockIndex, long blockSpan, byte[] data) {}

    // --- opening a stream -------------------------------------------------------------

    /**
     * Reads and verifies the magic, reads the (uncompressed) header, and returns the body
     * stream — brotli-decompressing or raw per the header's compression. The returned stream is
     * positioned at the first framed message of the body.
     */
    public static InputStream openBody(InputStream raw, int expectedMagic) throws IOException {
        int magic = readMagic(raw);
        if (magic != expectedMagic) {
            throw new IOException(String.format("bad wharf magic 0x%08X (expected 0x%08X)", magic, expectedMagic));
        }
        int algorithm = readCompressionAlgorithm(readFrame(raw));
        return switch (algorithm) {
            case COMP_NONE -> raw;
            case COMP_BROTLI -> new BrotliInputStream(raw);
            default -> throw new IOException("unsupported wharf compression algorithm " + algorithm);
        };
    }

    private static int readMagic(InputStream in) throws IOException {
        byte[] m = readFully(in, 4);
        return (m[0] & 0xff) | (m[1] & 0xff) << 8 | (m[2] & 0xff) << 16 | (m[3] & 0xff) << 24;
    }

    /** Header is {@code {compression{algorithm=1,…}}}; returns compression.algorithm. */
    private static int readCompressionAlgorithm(byte[] header) {
        Proto p = new Proto(header);
        int algorithm = COMP_NONE;
        while (p.hasMore()) {
            int tag = p.readTag();
            if (field(tag) == 1 && wire(tag) == 2) { // compression sub-message
                Proto c = new Proto(p.readLenDelim());
                while (c.hasMore()) {
                    int t = c.readTag();
                    if (field(t) == 1) algorithm = (int) c.readVarint();
                    else c.skip(wire(t));
                }
            } else {
                p.skip(wire(tag));
            }
        }
        return algorithm;
    }

    // --- message parsers --------------------------------------------------------------

    public static Container parseContainer(byte[] msg) {
        Container c = new Container();
        Proto p = new Proto(msg);
        while (p.hasMore()) {
            int tag = p.readTag();
            switch (field(tag)) {
                case 1 -> c.files.add(parseFile(p.readLenDelim()));
                case 2 -> c.dirs.add(parseDir(p.readLenDelim()));
                case 3 -> c.symlinks.add(parseSymlink(p.readLenDelim()));
                case 16 -> c.size = p.readVarint();
                default -> p.skip(wire(tag));
            }
        }
        return c;
    }

    private static FileEntry parseFile(byte[] msg) {
        Proto p = new Proto(msg);
        String path = ""; long mode = 0, size = 0, offset = 0;
        while (p.hasMore()) {
            int tag = p.readTag();
            switch (field(tag)) {
                case 1 -> path = p.readString();
                case 2 -> mode = p.readVarint();
                case 3 -> size = p.readVarint();
                case 4 -> offset = p.readVarint();
                default -> p.skip(wire(tag));
            }
        }
        return new FileEntry(path, mode, size, offset);
    }

    private static DirEntry parseDir(byte[] msg) {
        Proto p = new Proto(msg);
        String path = ""; long mode = 0;
        while (p.hasMore()) {
            int tag = p.readTag();
            switch (field(tag)) {
                case 1 -> path = p.readString();
                case 2 -> mode = p.readVarint();
                default -> p.skip(wire(tag));
            }
        }
        return new DirEntry(path, mode);
    }

    private static SymlinkEntry parseSymlink(byte[] msg) {
        Proto p = new Proto(msg);
        String path = "", dest = ""; long mode = 0;
        while (p.hasMore()) {
            int tag = p.readTag();
            switch (field(tag)) {
                case 1 -> path = p.readString();
                case 2 -> mode = p.readVarint();
                case 3 -> dest = p.readString();
                default -> p.skip(wire(tag));
            }
        }
        return new SymlinkEntry(path, mode, dest);
    }

    public static SyncHeader parseSyncHeader(byte[] msg) {
        Proto p = new Proto(msg);
        int type = 0; long fileIndex = 0;
        while (p.hasMore()) {
            int tag = p.readTag();
            switch (field(tag)) {
                case 1 -> type = (int) p.readVarint();
                case 16 -> fileIndex = p.readVarint();
                default -> p.skip(wire(tag));
            }
        }
        return new SyncHeader(type, fileIndex);
    }

    public static SyncOp parseSyncOp(byte[] msg) {
        Proto p = new Proto(msg);
        int type = 0, fileIndex = 0; long blockIndex = 0, blockSpan = 0; byte[] data = null;
        while (p.hasMore()) {
            int tag = p.readTag();
            switch (field(tag)) {
                case 1 -> type = (int) p.readVarint();
                case 2 -> fileIndex = (int) p.readVarint();
                case 3 -> blockIndex = p.readVarint();
                case 4 -> blockSpan = p.readVarint();
                case 5 -> data = p.readLenDelim();
                default -> p.skip(wire(tag));
            }
        }
        return new SyncOp(type, fileIndex, blockIndex, blockSpan, data);
    }

    // --- framed-message stream --------------------------------------------------------

    /** Reads one length-framed message, or {@code null} at a clean end of stream. */
    public static byte[] readFrame(InputStream in) throws IOException {
        long len = readVarint(in);
        if (len < 0) return null;
        return readFully(in, (int) len);
    }

    /** LEB128 varint from a stream; -1 on a clean EOF before any byte. */
    static long readVarint(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) return -1;
        long result = 0;
        int shift = 0, c = first;
        while (true) {
            result |= (long) (c & 0x7f) << shift;
            if ((c & 0x80) == 0) return result;
            shift += 7;
            c = in.read();
            if (c < 0) throw new EOFException("truncated varint");
        }
    }

    static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException("expected " + n + " bytes, got " + off);
            off += r;
        }
        return buf;
    }

    static int field(int tag) { return tag >>> 3; }
    static int wire(int tag) { return tag & 7; }

    /** Minimal protobuf field reader over a byte[]. */
    static final class Proto {
        private final byte[] b;
        private int pos;
        private final int end;

        Proto(byte[] b) { this.b = b; this.pos = 0; this.end = b.length; }

        boolean hasMore() { return pos < end; }

        long readVarint() {
            long result = 0;
            int shift = 0;
            while (true) {
                int c = b[pos++] & 0xff;
                result |= (long) (c & 0x7f) << shift;
                if ((c & 0x80) == 0) return result;
                shift += 7;
            }
        }

        int readTag() { return (int) readVarint(); }

        byte[] readLenDelim() {
            int len = (int) readVarint();
            byte[] out = Arrays.copyOfRange(b, pos, pos + len);
            pos += len;
            return out;
        }

        String readString() { return new String(readLenDelim(), StandardCharsets.UTF_8); }

        void skip(int wireType) {
            switch (wireType) {
                case 0 -> readVarint();
                case 1 -> pos += 8;
                case 2 -> { int len = (int) readVarint(); pos += len; }
                case 5 -> pos += 4;
                default -> throw new IllegalStateException("unknown wire type " + wireType);
            }
        }
    }
}
