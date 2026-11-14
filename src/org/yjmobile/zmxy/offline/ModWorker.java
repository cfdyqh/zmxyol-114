package org.yjmobile.zmxy.offline;

import android.util.Log;

/**
 * Drives libmod.so from a background thread.
 *
 * libgame.so is mapped by the activity (after Application.onCreate), and the
 * save layer only becomes meaningful once the town is up, so this waits for the
 * mapping and then for the scene to settle before touching anything.
 *
 * Declared as a top-level class on purpose: d8 crashes on the anonymous inner
 * class form here ("internal error" while reading EnclosingMethod).
 */
final class ModWorker implements Runnable {
    private static final String TAG = "YJOffline";

    @Override
    public void run() {
        try {
            long deadline = System.currentTimeMillis() + 60000L;
            while (Mod.probe() != 0) {
                if (System.currentTimeMillis() > deadline) {
                    Log.i(TAG, "libgame.so never loaded");
                    return;
                }
                Thread.sleep(200L);
            }
            Log.i(TAG, "libgame attached");
            Thread.sleep(9000L);
            Log.i(TAG, "setCurrencies -> " + Mod.setCurrencies(990000));

            // 货币保持 99 万：客户端切场景会重建管理器，把计数器重置回存档里的
            // 真实值。渲染线程钩子已经停用（雷电的 houdini 上不稳定），所以改成
            // 从本线程每 2 秒补一次。setCurrencies 只写 4 个整数，不创建 cocos
            // 对象，从非渲染线程调用是安全的（这里本来就在这么干）。
            for (int i = 0; i < 1200; i++) {      // ~1 小时
                int rc = Mod.setCurrencies(990000);
                if (i % 30 == 0) {
                    Log.i(TAG, "setCurrencies -> " + rc + (rc == -3 ? " (not ready)" : ""));
                }
                Thread.sleep(3000L);
            }

            // fixupRoleDesc disabled: the client frees the desc we install, so the
            // pointer becomes 0xdead1007 poison and skills crash later.
            Mod.requestDump(LocalServer.localSavePath());
            int found = Mod.dump();
            Log.i(TAG, "bagCount = " + Mod.bagCount());
            Log.i(TAG, "dump -> members=" + found);

            int vt = Mod.vtable44();
            Log.i(TAG, "vtable44 -> " + vt);

            // The 18x setJson push is gone on purpose. It re-injected a stale
            // copy of the save (readLocalSaveJson prefers files/offline/save.json,
            // which libmod wrote at some earlier save point) into a live session,
            // so it could resurrect old items and fight the real restore path.
            //
            // Restoring now happens where the client expects it to: the
            // "roledata2" reply carries "savedata" and
            // YJSaveLoadManager::analyzeAndApplyDataToGame applies it (it purges
            // and rebuilds every manager itself, so it must be the only writer).
        } catch (Throwable t) {
            Log.i(TAG, "mod worker failed: " + t);
        }
    }
}
