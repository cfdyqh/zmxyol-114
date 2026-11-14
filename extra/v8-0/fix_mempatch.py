# -*- coding: utf-8 -*-
"""把 jadx 反编译里被 try/finally 搅乱的 MemPatch.regions()/dumpLibgameSegments() 重写成干净实现。"""
import io

P = r"<tmp>/src\sources\org\yjmobile\zmxy\offline\MemPatch.java"

REGIONS = '''    static java.util.List<org.yjmobile.zmxy.offline.MemPatch.Region> regions() {
        java.util.ArrayList arrayList = new java.util.ArrayList();
        java.io.BufferedReader bufferedReader = null;
        try {
            bufferedReader = new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"));
            java.lang.String line;
            while ((line = bufferedReader.readLine()) != null) {
                java.lang.String[] parts = line.split("\\\\s+");
                if (parts.length >= 2) {
                    int dash = parts[0].indexOf(45);
                    if (dash > 0) {
                        try {
                            org.yjmobile.zmxy.offline.MemPatch.Region region =
                                    new org.yjmobile.zmxy.offline.MemPatch.Region();
                            region.start = java.lang.Long.parseLong(parts[0].substring(0, dash), 16);
                            region.end = java.lang.Long.parseLong(parts[0].substring(dash + 1), 16);
                            region.writable = parts[1].length() > 1 && parts[1].charAt(1) == 'w';
                            region.path = parts.length >= 6 ? parts[5] : "";
                            arrayList.add(region);
                        } catch (java.lang.NumberFormatException e) {
                        }
                    }
                }
            }
        } catch (java.lang.Throwable th) {
            android.util.Log.e(TAG, "mem: maps failed", th);
        } finally {
            if (bufferedReader != null) {
                try {
                    bufferedReader.close();
                } catch (java.io.IOException e2) {
                }
            }
        }
        return arrayList;
    }

'''

DUMP = '''    static void dumpLibgameSegments(android.content.Context context) {
        // 调试专用（devTap 手势触发），8.0 mod 重编译时简化成空实现。
        android.util.Log.i(TAG, "dump: skipped");
    }

'''

with io.open(P, encoding="utf-8") as f:
    src = f.read()

a = src.index("    static java.util.List<org.yjmobile.zmxy.offline.MemPatch.Region> regions() {")
b = src.index("    static int replace(byte[] bArr, byte[] bArr2) {")
src = src[:a] + REGIONS + src[b:]

a = src.index("    static void dumpLibgameSegments(")
b = src.index("    static synchronized boolean dumpOnce() {")
src = src[:a] + DUMP + src[b:]

with io.open(P, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("rewritten, lines:", src.count("\n"))
