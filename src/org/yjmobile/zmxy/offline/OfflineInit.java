package org.yjmobile.zmxy.offline;

import android.app.Application;

/**
 * 备用入口（当前 manifest 用的是乐固壳的 zmxyApplication，
 * 真正的启动逻辑在 {@link Boot#start}，由 {@link OfflineProvider} 触发）。
 */
public class OfflineInit extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            // 仅用于「带 Frida 的调试包」：把 libgadget.so 加载进来，非 root 真机也能 attach。
            // 两个必须注意的点（真机实测）：
            //  1) 不能在主线程 dlopen 23 MB 的 gadget，否则 MIUI 判 ANR → 黑屏被杀；
            //  2) 只能在本进程加载——这个包还带 :xg_service_v2 子进程，我们的
            //     Application 在子进程里也会跑，重复加载会抢 27042 端口。
            boolean mainProc = true;
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream("/proc/self/cmdline")));
                String cmd = r.readLine();
                r.close();
                if (cmd != null && cmd.indexOf(':') >= 0) {
                    mainProc = false;
                }
            } catch (Throwable ignored) {
            }
            if (mainProc) {
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            System.loadLibrary("gadget");
                            android.util.Log.i("YJOffline", "frida gadget loaded");
                        } catch (Throwable t) {
                            android.util.Log.i("YJOffline", "frida gadget not present: " + t);
                        }
                    }
                }, "yjgadget").start();
            }
        } catch (Throwable t) {
            android.util.Log.i("YJOffline", "frida gadget thread failed: " + t);
        }
        try {
            // 补上原版 zmxyApplication/TPShellApplication 那条链里被跳过的 TP2 初始化。
            // 去乐固包实测会 exit(15)，怀疑就是 libgame 的 tp2_* 胶水在未初始化的
            // TP2 上判定失败；这里用魔数 19006（TP2 配置 URL 里的 app id）先补一次。
            Class<?> c = Class.forName("com.tencent.tersafe2.TP2Sdk");
            java.lang.reflect.Method m = c.getMethod("init", int.class);
            Object r = m.invoke(null, 19006);
            android.util.Log.i("YJOffline", "tp2 TP2Sdk.init(19006) -> " + r);
        } catch (Throwable t) {
            android.util.Log.i("YJOffline", "tp2 init failed: " + t);
        }
        Boot.start(this);
    }
}
