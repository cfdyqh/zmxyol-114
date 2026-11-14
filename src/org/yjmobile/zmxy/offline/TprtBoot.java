package org.yjmobile.zmxy.offline;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 手动跑一次乐固的 native initialize()。
 *
 * 去乐固包没有走 TPShellApplication.attachBaseContext，libtprt 的运行时状态
 * （g_tprt_pfn_array / g_tprt_ori_array / unwind 表）就没被填过，
 * libgame 依赖它，nativeInit 里判定异常直接 exit(15)。
 * 这里反射调用它自己的私有 native 方法把状态补齐。
 */
final class TprtBoot {
    private static final String TAG = "YJOffline";

    private TprtBoot() {
    }

    static void init(Context ctx) {
        try {
            Class<?> c = Class.forName("com.tencent.tpshell.TPShellApplication");
            if (ctx == null) {
                Class<?> at = Class.forName("android.app.ActivityThread");
                java.lang.reflect.Method ca = at.getDeclaredMethod("currentApplication");
                ca.setAccessible(true);
                ctx = (android.content.Context) ca.invoke(null);
            }
            Object inst = c.getDeclaredConstructor().newInstance();
            String pkg = ctx.getPackageName();
            String dataDir = ctx.getApplicationInfo().dataDir;
            String libDir = ctx.getApplicationInfo().nativeLibraryDir;
            String srcDir = ctx.getApplicationInfo().sourceDir;
            String proc = procName();
            Method m = c.getDeclaredMethod("initialize", String.class, String.class,
                    String.class, String.class, String.class, String.class);
            m.setAccessible(true);
            Object r = m.invoke(inst, pkg, dataDir, libDir, srcDir, proc, dataDir);
            Log.i(TAG, "tprt initialize -> " + r);
            try {
                Method rel = c.getDeclaredMethod("release");
                rel.setAccessible(true);
                rel.invoke(null);
                Log.i(TAG, "tprt release ok");
            } catch (Throwable t) {
                Log.i(TAG, "tprt release failed: " + t);
            }
        } catch (Throwable t) {
            Log.i(TAG, "tprt init failed: " + t);
        }
    }

    private static String procName() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream("/proc/self/cmdline")));
            String line = r.readLine();
            r.close();
            if (line != null) {
                int z = line.indexOf('\0');
                return z > 0 ? line.substring(0, z) : line;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }
}
