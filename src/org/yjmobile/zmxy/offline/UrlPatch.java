package org.yjmobile.zmxy.offline;

import android.util.Log;

/**
 * 把 libgame.so 运行时解密出来的服务器地址改成本地服。
 *
 * 11.4.0 的 libgame.so 里 URL 是加密的（文件里搜不到），运行时解密进
 * 一个 rw 映射（实测在 0x4cf9xxx），所以只能扫 /proc/self/mem。
 * 客户端还会把 URL 拷进自己的堆字符串，因此要持续扫一段时间。
 */
final class UrlPatch {
    private static final String TAG = "YJOffline";

    private static final String URL_API = "http<path>";
    private static final String URL_CHAT = "http<path>";
    private static final String URL_WB = "http<path>";
    /* Device port 8077 is served by our in-process LocalServer, which also honours
       the /sdcard/yj/<action>.json override for hot iteration. */
    private static final String NEW_API = "htt<path>";
    private static final String NEW_CHAT = "htt<path>";
    private static final String NEW_WB = "htt<path>";

    private UrlPatch() {
    }

    static void watch() {
        byte[][] from = {bytes(URL_API), bytes(URL_CHAT), bytes(URL_WB)};
        byte[][] to = {bytes(NEW_API), bytes(NEW_CHAT), bytes(NEW_WB)};
        boolean[] live = new boolean[from.length];
        long deadline = System.currentTimeMillis() + 180000L;
        int pass = 0;
        while (System.currentTimeMillis() < deadline) {
            pass++;
            for (int i = 0; i < from.length; i++) {
                int n = 0;
                try {
                    n = MemPatch.replace(from[i], to[i]);
                } catch (Throwable t) {
                    Log.e(TAG, "patch: url[" + i + "] failed", t);
                }
                if (n > 0 && !live[i]) {
                    live[i] = true;
                    Log.i(TAG, "patch: url[" + i + "] live (" + n + ") pass=" + pass);
                }
            }
            try {
                Thread.sleep(pass < 60 ? 60 : 400);
            } catch (InterruptedException e) {
                return;
            }
        }
        Log.i(TAG, "patch: watchdog done");
    }

    private static byte[] bytes(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return s.getBytes();
        }
    }
}
