package org.yjmobile.zmxy.offline;

import android.content.Context;
import android.util.Log;

/**
 * Background boot for v8.0.0.
 *
 * Only what the client needs before its first POST stays here; Application.onCreate
 * itself is kept cheap (a 300MB APK on a slow device ANRs otherwise).
 */
final class EarlyBoot implements Runnable {
    private static final String TAG = "YJOffline";

    private final Context ctx;

    EarlyBoot(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    @Override
    public void run() {
        // StartDialog is installed on the main thread from OfflineInit.onCreate.
        try {
            LocalServer.refreshDataTables(ctx);
        } catch (Throwable t) {
            Log.i(TAG, "refreshDataTables failed: " + t);
        }
        try {
            LocalServer.start(ctx);
        } catch (Throwable t) {
            Log.i(TAG, "LocalServer.start failed: " + t);
        }
        Log.i(TAG, "early boot done");
    }
}
