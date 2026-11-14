package org.yjmobile.zmxy.offline;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * In-process string patcher.
 *
 * v8.0.0 keeps every string literal encrypted inside libgame.so and decrypts the
 * whole pool into an anonymous rw mapping at startup (a 4.3MB NUL-separated
 * blob we found at 0x463xxxx).  Because that copy is writable, we can rewrite
 * the API URL there instead of touching the encrypted blob on disk.
 */
public final class MemPatch {
    private static final String TAG = "YJOffline";

    private MemPatch() {
    }

    /** A parsed /proc/self/maps line. */
    static final class Region {
        long start;
        long end;
        boolean writable;
        String path;
    }

    static List<Region> regions() {
        List<Region> out = new ArrayList<Region>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/self/maps"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\\s+");
                if (f.length < 2) {
                    continue;
                }
                int dash = f[0].indexOf('-');
                if (dash <= 0) {
                    continue;
                }
                Region g = new Region();
                try {
                    g.start = Long.parseLong(f[0].substring(0, dash), 16);
                    g.end = Long.parseLong(f[0].substring(dash + 1), 16);
                } catch (NumberFormatException e) {
                    continue;
                }
                g.writable = f[1].length() > 1 && f[1].charAt(1) == 'w';
                g.path = f.length >= 6 ? f[5] : "";
                out.add(g);
            }
        } catch (Throwable t) {
            Log.e(TAG, "mem: maps failed", t);
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (IOException ignored) {
                }
            }
        }
        return out;
    }

    /**
     * Replace the first occurrence of {@code needle} in every candidate region.
     * The replacement must not be longer than the needle; it is NUL padded.
     *
     * The Java heap is skipped on purpose: our own needle constant lives there
     * (a byte[]), and patching it would both corrupt the search key and waste
     * passes.  The game's strings are native allocations, not ART objects.
     *
     * @return number of replacements
     */
    static int replace(byte[] needle, byte[] repl) {
        if (repl.length > needle.length) {
            throw new IllegalArgumentException("replacement longer than needle");
        }
        byte[] padded = new byte[needle.length];
        System.arraycopy(repl, 0, padded, 0, repl.length);
        int hits = 0;
        RandomAccessFile mem = null;
        try {
            mem = new RandomAccessFile("/proc/self/mem", "rw");
            for (Region g : regions()) {
                if (!g.writable) {
                    continue;
                }
                long size = g.end - g.start;
                if (size < needle.length || size > (32L << 20)) {
                    continue;
                }
                if (g.path.contains("dalvik") || g.path.contains("boot.art")
                        || g.path.contains("boot.oat") || g.path.contains("base.apk")) {
                    continue;
                }
                hits += scanRegion(mem, g, needle, padded);
            }
        } catch (Throwable t) {
            Log.e(TAG, "mem: open failed", t);
        } finally {
            if (mem != null) {
                try {
                    mem.close();
                } catch (IOException ignored) {
                }
            }
        }
        return hits;
    }

    private static int scanRegion(RandomAccessFile mem, Region g, byte[] needle, byte[] repl)
            throws IOException {
        final int chunk = 1 << 20;
        byte[] buf = new byte[chunk + needle.length];
        int hits = 0;
        long pos = g.start;
        while (pos < g.end) {
            int want = (int) Math.min(chunk, g.end - pos);
            mem.seek(pos);
            int got;
            try {
                got = mem.read(buf, 0, want);
            } catch (IOException e) {
                pos += want;
                continue;
            }
            if (got <= 0) {
                pos += want;
                continue;
            }
            int limit = got - needle.length;
            for (int i = 0; i <= limit; i++) {
                if (!match(buf, i, needle)) {
                    continue;
                }
                long at = pos + i;
                try {
                    mem.seek(at);
                    mem.write(repl);
                    hits++;
                    Log.i(TAG, "mem: patched " + new String(needle, "UTF-8") + " @0x"
                            + Long.toHexString(at));
                } catch (IOException e) {
                    Log.e(TAG, "mem: write failed @0x" + Long.toHexString(at), e);
                }
                i += needle.length - 1;
            }
            pos += got;
        }
        return hits;
    }

    private static boolean match(byte[] buf, int off, byte[] needle) {
        for (int i = 0; i < needle.length; i++) {
            if (buf[off + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }
}
