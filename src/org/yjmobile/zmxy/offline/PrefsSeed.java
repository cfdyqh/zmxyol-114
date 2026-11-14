package org.yjmobile.zmxy.offline;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 把"能进游戏的那台手机"上的 4399 SDK 缓存搬进 APK。
 *
 * 背景：4399 的 SDK 配置接口已经下线（现在返回 {"code":"99","message":"服务已关闭"}），
 * 有的机器能过、有的机器卡在"初始化中"。能过的机器上一定有 SDK 自己留下的缓存
 * （账号态 / sdkconf / 登录方式列表之类），把那份缓存原样带到别的机器上，
 * SDK 就不用联网也能完成 init。
 *
 * 两步走：
 *   1) seed(ctx) —— 启动时把 assets/seed_prefs 里打包好的缓存文件写进
 *      dataDir/shared_prefs（目标已存在就跳过，不覆盖玩家机器上的新状态）。
 *   2) dump(ctx) —— 把本机的 shared_prefs / files / databases 里的缓存复制到
 *      getExternalFilesDir("prefs")，这样 adb 能直接 pull 出来，不需要 root。
 *
 * 两个动作都是尽力而为，任何异常都吞掉，绝不影响游戏启动。
 */
final class PrefsSeed {
    private static final String TAG = "YJOffline";
    private static final String SEED_ASSET_DIR = "seed_prefs";

    private PrefsSeed() {
    }

    // ---------------------------------------------------------------- seed
    static void seed(Context ctx) {
        try {
            String[] names = ctx.getAssets().list(SEED_ASSET_DIR);
            if (names == null || names.length == 0) {
                return;
            }
            File prefs = new File(ctx.getApplicationInfo().dataDir, "shared_prefs");
            if (!prefs.exists()) {
                prefs.mkdirs();
            }
            int n = 0;
            for (String name : names) {
                File dst = new File(prefs, name);
                if (dst.exists()) {
                    continue;            // never clobber what the device already has
                }
                InputStream in = ctx.getAssets().open(SEED_ASSET_DIR + "/" + name);
                OutputStream out = new FileOutputStream(dst);
                copy(in, out);
                n++;
            }
            Log.i(TAG, "seed: restored " + n + " prefs file(s)");
        } catch (Throwable t) {
            Log.i(TAG, "seed failed: " + t);
        }
    }

    // -------------------------------------------------------------- import
    /**
     * Adopt an existing save on first run, so the coexisting (renamed) build can
     * continue where the normal one left off. Looks, in order, at
     *   <external files>/import_savedata.json
     *   /sdcard/Download/zmxy_savedata.json
     * and only acts when this install has no store yet.
     */
    static void importSave(Context ctx) {
        try {
            File store = new File(ctx.getApplicationInfo().dataDir,
                    "files/offline/savedata.json");
            if (store.exists() && store.length() > 2) {
                return;                       // already have one, never overwrite
            }
            File dir = new File(ctx.getApplicationInfo().dataDir, "files/offline");
            dir.mkdirs();
            File[] candidates = new File[]{
                    new File(ctx.getExternalFilesDir(null), "import_savedata.json"),
                    new File("/sdcard/Download/zmxy_savedata.json"),
                    new File("/sdcard/zmxy_savedata.json"),
            };
            for (File c : candidates) {
                if (c != null && c.isFile() && c.length() > 2) {
                    FileInputStream in = new FileInputStream(c);
                    FileOutputStream os = new FileOutputStream(store);
                    copy(in, os);
                    Log.i(TAG, "importSave: adopted " + c);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "importSave failed: " + t);
        }
    }

    // ---------------------------------------------------------------- dump
    static void dump(Context ctx) {
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext == null) {
                return;
            }
            File out = new File(ext, "prefs");
            out.mkdirs();
            String data = ctx.getApplicationInfo().dataDir;
            int n = 0;
            n += dumpDir(new File(data, "shared_prefs"), new File(out, "shared_prefs"));
            n += dumpDir(new File(data, "databases"), new File(out, "databases"));
            n += dumpDir(new File(data, "files"), new File(out, "files"));
            // our own save store (plain JSON) - exported so the progress can be
            // carried over to another install (e.g. the renamed coexisting build)
            n += dumpFile(new File(data, "files/offline/savedata.json"),
                          new File(out, "offline_savedata.json"));
            n += dumpFile(new File(data, "files/offline/profile.json"),
                          new File(out, "offline_profile.json"));
            Log.i(TAG, "dump: copied " + n + " file(s) to " + out);
        } catch (Throwable t) {
            Log.i(TAG, "dump failed: " + t);
        }
    }

    private static int dumpFile(File src, File dst) throws Exception {
        if (!src.isFile() || src.length() == 0) {
            return 0;
        }
        dst.getParentFile().mkdirs();
        FileInputStream in = new FileInputStream(src);
        FileOutputStream os = new FileOutputStream(dst);
        copy(in, os);
        return 1;
    }

    private static int dumpDir(File src, File dst) throws Exception {
        if (!src.isDirectory()) {
            return 0;
        }
        // skip our own store, it is large and irrelevant to the SDK
        if ("offline".equals(src.getName())) {
            return 0;
        }
        dst.mkdirs();
        File[] files = src.listFiles();
        if (files == null) {
            return 0;
        }
        int n = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                n += dumpDir(f, new File(dst, f.getName()));
                continue;
            }
            if (f.length() > 2 * 1024 * 1024) {
                continue;
            }
            FileInputStream in = new FileInputStream(f);
            FileOutputStream os = new FileOutputStream(new File(dst, f.getName()));
            copy(in, os);
            n++;
        }
        return n;
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        try {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
