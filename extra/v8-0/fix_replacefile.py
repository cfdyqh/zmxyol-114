# -*- coding: utf-8 -*-
"""重写 MemPatch.replaceFile()：jadx 把 try/finally 搅乱，导致 catch 里 return 和缺 return。"""
import io

P = r"<tmp>/src\sources\org\yjmobile\zmxy\offline\MemPatch.java"

NEW = '''    private static int replaceFile(byte[] bArr, byte[] bArr2) {
        byte[] bArr3 = new byte[bArr.length];
        int iScanRegion = 0;
        java.lang.System.arraycopy(bArr2, 0, bArr3, 0, bArr2.length);
        java.io.RandomAccessFile randomAccessFile = null;
        try {
            randomAccessFile = new java.io.RandomAccessFile("/proc/self/mem", "rw");
            for (org.yjmobile.zmxy.offline.MemPatch.Region region : regions()) {
                if (!region.writable) {
                    continue;
                }
                long j = region.end - region.start;
                if (j >= bArr.length && j <= 33554432
                        && !region.path.contains("dalvik")
                        && !region.path.contains("boot.art")
                        && !region.path.contains("boot.oat")
                        && !region.path.contains("base.apk")) {
                    iScanRegion += scanRegion(randomAccessFile, region, bArr, bArr3);
                }
            }
            return iScanRegion;
        } catch (java.lang.Throwable th) {
            android.util.Log.i(TAG, "mem: /proc/self/mem unavailable ("
                    + th.getClass().getSimpleName() + "), falling back to Unsafe");
            return -1;
        } finally {
            if (randomAccessFile != null) {
                try {
                    randomAccessFile.close();
                } catch (java.io.IOException e) {
                }
            }
        }
    }

'''

with io.open(P, encoding="utf-8") as f:
    src = f.read()

a = src.index("    private static int replaceFile(byte[] bArr, byte[] bArr2) {")
b = src.index("    private static int scanRegion(", a)
src = src[:a] + NEW + src[b:]

with io.open(P, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("ok")
