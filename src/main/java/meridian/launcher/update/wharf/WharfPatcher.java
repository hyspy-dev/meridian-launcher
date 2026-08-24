package meridian.launcher.update.wharf;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies a wharf {@code .pwr} patch: rebuilds the TARGET build from an existing SOURCE install,
 * writing the result into a fresh staging directory (never in place — a file can be both a
 * patch source and a target, so overwriting mid-apply would corrupt it). The caller swaps the
 * staging tree in atomically once every file verifies.
 *
 * <p>Each rebuilt file is a sequence of ops: {@code BLOCK_RANGE} copies a run of 64 KiB blocks
 * from the SOURCE file the op names (the final block clamped to the target file's size), and
 * {@code DATA} writes literal bytes. A file that does not reconstruct to its declared target
 * size aborts the whole apply.
 */
public final class WharfPatcher {

    private static final Logger log = LoggerFactory.getLogger(WharfPatcher.class);
    private static final int COPY_BUFFER = 1 << 20; // 1 MiB

    /** Reports cumulative bytes written against the total to write. */
    public interface Progress {
        void onBytes(long done, long total);
    }

    private WharfPatcher() {
    }

    public static void apply(Path patchFile, Path sourceDir, Path stagingDir, Progress progress)
            throws IOException {
        try (InputStream raw = Files.newInputStream(patchFile)) {
            InputStream body = Wharf.openBody(raw, Wharf.PATCH_MAGIC);
            Wharf.Container source = Wharf.parseContainer(require(Wharf.readFrame(body), "source container"));
            Wharf.Container target = Wharf.parseContainer(require(Wharf.readFrame(body), "target container"));

            Files.createDirectories(stagingDir);
            for (Wharf.DirEntry d : target.dirs) {
                Files.createDirectories(resolve(stagingDir, d.path()));
            }

            long total = target.size;
            long[] done = {0};
            Map<Integer, FileChannel> sources = new HashMap<>();
            ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER);
            try {
                for (int i = 0; i < target.files.size(); i++) {
                    Wharf.SyncHeader header = Wharf.parseSyncHeader(
                            require(Wharf.readFrame(body), "sync header for file " + i));
                    if (header.type() != 0) {
                        throw new IOException("file " + i + " uses unsupported sync type " + header.type()
                                + " (only RSYNC is implemented)");
                    }
                    Wharf.FileEntry tf = target.files.get(i);
                    long written = rebuildFile(body, tf, source, sources, sourceDir, stagingDir, buffer,
                            total, done, progress);
                    if (written != tf.size()) {
                        throw new IOException("file '" + tf.path() + "' rebuilt to " + written
                                + " bytes, expected " + tf.size());
                    }
                }
            } finally {
                for (FileChannel ch : sources.values()) {
                    try { ch.close(); } catch (IOException ignored) { }
                }
            }

            for (Wharf.SymlinkEntry s : target.symlinks) {
                createSymlink(stagingDir, s);
            }
            if (progress != null) progress.onBytes(total, total);
            log.info("Applied patch {} → {} files ({} bytes)", patchFile.getFileName(),
                    target.files.size(), total);
        }
    }

    private static long rebuildFile(InputStream body, Wharf.FileEntry tf, Wharf.Container source,
                                    Map<Integer, FileChannel> sources, Path sourceDir, Path stagingDir,
                                    ByteBuffer buffer, long total, long[] done, Progress progress)
            throws IOException {
        Path out = resolve(stagingDir, tf.path());
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        long written = 0;
        try (OutputStream os = new BufferedOutputStream(
                Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            while (true) {
                Wharf.SyncOp op = Wharf.parseSyncOp(require(Wharf.readFrame(body), "op in '" + tf.path() + "'"));
                if (op.type() == Wharf.OP_HEY_YOU_DID_IT) break;
                long chunk;
                if (op.type() == Wharf.OP_DATA) {
                    os.write(op.data());
                    chunk = op.data().length;
                } else if (op.type() == Wharf.OP_BLOCK_RANGE) {
                    chunk = Math.min(op.blockSpan() * Wharf.BLOCK, tf.size() - written);
                    copyBlocks(sourceChannel(sources, source, sourceDir, op.fileIndex()),
                            op.blockIndex() * Wharf.BLOCK, chunk, os, buffer);
                } else {
                    throw new IOException("unknown sync op type " + op.type() + " in '" + tf.path() + "'");
                }
                written += chunk;
                done[0] += chunk;
                if (progress != null) progress.onBytes(Math.min(done[0], total), total);
            }
        }
        return written;
    }

    private static void copyBlocks(FileChannel src, long position, long length, OutputStream out,
                                   ByteBuffer buffer) throws IOException {
        long remaining = length;
        long pos = position;
        while (remaining > 0) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), remaining));
            int read = src.read(buffer, pos);
            if (read < 0) {
                throw new IOException("source exhausted at " + pos + ", " + remaining + " bytes short");
            }
            out.write(buffer.array(), 0, read);
            pos += read;
            remaining -= read;
        }
    }

    private static FileChannel sourceChannel(Map<Integer, FileChannel> cache, Wharf.Container source,
                                             Path sourceDir, int index) throws IOException {
        FileChannel ch = cache.get(index);
        if (ch == null) {
            Path p = resolve(sourceDir, source.files.get(index).path());
            ch = FileChannel.open(p, StandardOpenOption.READ);
            cache.put(index, ch);
        }
        return ch;
    }

    private static void createSymlink(Path stagingDir, Wharf.SymlinkEntry s) {
        try {
            Path link = resolve(stagingDir, s.path());
            if (link.getParent() != null) Files.createDirectories(link.getParent());
            Files.deleteIfExists(link);
            Files.createSymbolicLink(link, Path.of(s.dest()));
        } catch (IOException | UnsupportedOperationException e) {
            log.warn("Could not create symlink {} -> {}: {}", s.path(), s.dest(), e.toString());
        }
    }

    /** Resolves a container-relative path (either separator) safely under {@code base}. */
    private static Path resolve(Path base, String relative) {
        Path p = base;
        for (String seg : relative.replace('\\', '/').split("/")) {
            if (!seg.isBlank() && !seg.equals(".") && !seg.equals("..")) {
                p = p.resolve(seg);
            }
        }
        return p;
    }

    private static byte[] require(byte[] frame, String what) throws IOException {
        if (frame == null) throw new IOException("patch stream ended early, expected " + what);
        return frame;
    }
}
