package org.yjmobile.zmxy.offline;

import android.content.Context;
import android.util.Log;

/**
 * 离线启动逻辑（和 Application 解耦）。
 *
 * 11.4.0 的 manifest 里 application 是乐固壳（zmxyApplication extends
 * TPShellApplication），**不能**换掉：实测换成自己的 Application 后，
 * 进程在 libtersafe2/libgame 加载后 1 秒被 SIGTERM 掉（壳/反外挂依赖
 * 乐固初始化过的状态）。所以改成占一个 ContentProvider 的位置：
 * provider 的 onCreate 比 Application.onCreate 还早，在同一个进程里，
 * 而且我们的类在 classes2.dex，类加载器照样找得到。
 */
final class Boot {
    private static final String TAG = "YJOffline";
    private static volatile boolean sStarted;

    private Boot() {
    }

    static synchronized void start(Context ctx) {
        if (sStarted) {
            return;
        }
        sStarted = true;
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        Log.i(TAG, "Boot.start pid=" + android.os.Process.myPid() + " pkg=" + app.getPackageName());

        // 这个包还带 :xg_service_v2 子进程（信鸽推送），我们的 provider 在子进程里也会被创建。
        // 子进程如果也跑 EarlyBoot，会：① 把 libgame 再加载一遍；② 抢先占住 127.0.0.1:8077，
        // 导致主进程的 LocalServer 绑不上（真机实测：更新检查失败，网络连接出错(0)）。
        // 所以非主进程直接不启动。
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream("/proc/self/cmdline")));
            String cmd = r.readLine();
            r.close();
            if (cmd != null && cmd.indexOf(':') >= 0) {
                Log.i(TAG, "secondary process, skip boot: " + cmd);
                return;
            }
        } catch (Throwable ignored) {
        }

        try {
            java.io.File ext = app.getExternalFilesDir(null);
            if (ext != null) {
                new java.io.File(ext, "assets").mkdirs();
                new java.io.File(ext.getParentFile(), "cache").mkdirs();
                Log.i(TAG, "external dirs ready: " + ext);
            }
        } catch (Throwable t) {
            Log.i(TAG, "external dir setup failed: " + t);
        }

        // 一次性重置客户端本地缓存（背包/法宝槽存在客户端侧，服务端清空
        // 不会让客户端丢掉已买的物品）。存在 /sdcard/yj/reset_client 时，
        // 把 savedata.dat / UserDefault.xml / b / caches 改名成 .bak，
        // 下次启动客户端从服务端重新拉空背包。原文件保留，可手动恢复。
        try {
            if (new java.io.File("/sdcard/yj/reset_client").exists()) {
                java.io.File files = app.getFilesDir();
                java.io.File dataDir = files == null ? null : files.getParentFile();
                java.io.File[] targets = {
                        files == null ? null : new java.io.File(files, "savedata.dat"),
                        dataDir == null ? null : new java.io.File(dataDir, "UserDefault.xml"),
                        files == null ? null : new java.io.File(files, "b"),
                        files == null ? null : new java.io.File(files, "caches"),
                };
                for (int i = 0; i < targets.length; i++) {
                    java.io.File f = targets[i];
                    if (f != null && f.exists()) {
                        java.io.File bak = new java.io.File(f.getParentFile(), f.getName() + ".resetbak");
                        if (bak.exists()) {
                            bak.delete();
                        }
                        boolean ok = f.renameTo(bak);
                        Log.i(TAG, "reset_client rename " + f.getName() + " -> " + ok);
                    }
                }
                new java.io.File("/sdcard/yj/reset_client").delete();
                java.io.FileOutputStream fo = new java.io.FileOutputStream("/sdcard/yj/reset_client_done");
                fo.write(1);
                fo.close();
            }
        } catch (Throwable t) {
            Log.i(TAG, "reset_client failed: " + t);
        }

        // 先把本地服拉起来：客户端第一次检查更新可能在引擎初始化后 1~2 秒内就发出，
        // 而 yjboot 线程还要做数据表刷新等准备工作。真机上实测会撞成
        // 「更新检查失败，网络连接出错(0)」（连不上 127.0.0.1:8077）。
        // start() 自带 sStarted 幂等保护，EarlyBoot 里那次重复调用是空操作。
        // 关掉音效/音乐：小米 HyperOS 的音频栈在 SoundPool 建 AudioTrack 时会踩
        // strlen(NULL)（isAppNeedtoChangeStreamType，SIGSEGV，崩在 soundpool 线程），
        // 真机进关卡一放音效就死。cocos2d-x 的开关就在 Cocos2dxPrefsFile 里
        // （Music / Sound 两个 boolean）。想开声音就建 /sdcard/yj/sound。
        try {
            android.content.SharedPreferences sp =
                    app.getSharedPreferences("Cocos2dxPrefsFile", Context.MODE_PRIVATE);
            boolean wantSound = new java.io.File("/sdcard/yj/sound").exists();
            if (wantSound) {
                if (!sp.getBoolean("Sound", true) || !sp.getBoolean("Music", true)) {
                    sp.edit().putBoolean("Sound", true).putBoolean("Music", true).commit();
                    Log.i(TAG, "audio enabled by /sdcard/yj/sound");
                }
            } else if (sp.getBoolean("Sound", true) || sp.getBoolean("Music", true)) {
                sp.edit().putBoolean("Sound", false).putBoolean("Music", false).commit();
                Log.i(TAG, "audio muted (SoundPool crash workaround; create /sdcard/yj/sound to enable)");
            }
        } catch (Throwable t) {
            Log.i(TAG, "audio pref failed: " + t);
        }

        try {
            // 先把内嵌的 /sdcard/yj/ 配置写出来（换设备只装 APK 就能跑）。
            // login.json / login_enterserver.json 缺失会让客户端在 GL 线程原生崩溃。
            YjSeed.seed();
        } catch (Throwable t) {
            Log.i(TAG, "YjSeed.seed failed: " + t);
        }

        try {
            LocalServer.start(app);
            Log.i(TAG, "offline server requested early");
        } catch (Throwable t) {
            Log.i(TAG, "early LocalServer.start failed: " + t);
        }

        try {
            Thread boot = new Thread(new EarlyBoot(app), "yjboot");
            boot.setDaemon(true);
            boot.start();
        } catch (Throwable t) {
            Log.i(TAG, "boot thread failed: " + t);
        }

        try {
            FnForce.schedule();
        } catch (Throwable t) {
            Log.i(TAG, "FnForce schedule failed: " + t);
        }

        try {
            if (app instanceof android.app.Application) {
                SkipButton.install((android.app.Application) app);
            }
        } catch (Throwable t) {
            Log.i(TAG, "skip button install failed: " + t);
        }

        try {
            if (app instanceof android.app.Application) {
                SpiritButton.install((android.app.Application) app);
            }
        } catch (Throwable t) {
            Log.i(TAG, "spirit button install failed: " + t);
        }

        try {
            if (new java.io.File("/sdcard/yj/debug").exists() && app instanceof android.app.Application) {
                TapIn.install((android.app.Application) app);
            }
        } catch (Throwable t) {
            Log.i(TAG, "tapin install failed: " + t);
        }

        // 只有能读 /proc/self/mem 的环境才跑 Java 版 URL 补丁（root 过的模拟器可以）。
        // 真机非 root / 非 debuggable 时 SELinux 直接 EACCES，这个线程每 60ms 抛一次异常，
        // 既刷屏又拖慢启动。真机上改由 libtprt shim 在 native 层直接改自己进程内存。
        boolean canScanMem = false;
        try {
            java.io.File f = new java.io.File("/proc/self/mem");
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            in.read();
            in.close();
            canScanMem = true;
        } catch (Throwable ignored) {
        }
        if (canScanMem) {
            Thread p = new Thread(new Runnable() {
                public void run() {
                    UrlPatch.watch();
                }
            }, "yjpatch");
            p.setDaemon(true);
            p.start();
        } else {
            Log.i(TAG, "patch: /proc/self/mem unavailable, native urlpatch takes over");
        }
    }
}
