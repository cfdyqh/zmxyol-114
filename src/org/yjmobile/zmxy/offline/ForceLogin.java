package org.yjmobile.zmxy.offline;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * 4.1.1 的"跳过 4399 登录"补丁。
 *
 * 4.1.1 把整条网络流程搬进了 libgame，游戏只有在 Java 层收到
 *   4399 SDK 的 init/login 回调后，才会通过 JNI 通知 native：
 *     org.yjmobile.lib.YJMobileJNIHelper.native4399LoginFinished(String,String,String)
 * 而 4399 的登录服务早已关服（openapi/oauth.html 只回"服务已关闭"），
 * SDK 的 init 甚至没有被调用（反射查不到 listener），所以 native 永远等不到这一步，
 * 也就永远不发 login 请求。
 *
 * 这里直接反射调用那个 JNI 接口，把"登录成功"喂给 native。
 * 只在迟迟没有 login 请求时才动手，正常机器是 no-op。
 */
final class ForceLogin implements Runnable {
    private static final String TAG = "YJOffline";

    private static volatile boolean sDone = false;

    private ForceLogin() {
    }

    static void schedule() {
        try {
            Thread t = new Thread(new ForceLogin(), "yjforcelogin");
            t.setDaemon(true);
            t.start();
            Log.i(TAG, "forcelogin: watchdog started");
        } catch (Throwable t) {
            Log.i(TAG, "forcelogin schedule failed: " + t);
        }
    }

    @Override
    public void run() {
        for (int i = 0; i < 30; i++) {
            try {
                Thread.sleep(3000L);
            } catch (InterruptedException ignored) {
                return;
            }
            if (sDone) {
                return;
            }
            if (LocalServer.sawLogin()) {
                sDone = true;
                Log.i(TAG, "forcelogin: login already happened, skip");
                return;
            }
            if (i < 4) {
                continue;                       // give the game 12s to do it itself
            }
            if (fire()) {
                sDone = true;
                Log.i(TAG, "forcelogin: native4399LoginFinished fired after "
                        + ((i + 1) * 3) + "s");
                return;
            }
        }
        Log.i(TAG, "forcelogin: gave up");
    }

    private static boolean fire() {
        try {
            Class<?> helper = Class.forName("org.yjmobile.lib.YJMobileJNIHelper");
            Method target = null;
            for (Method m : helper.getDeclaredMethods()) {
                String n = m.getName();
                if (!n.startsWith("native4399") && !n.toLowerCase().contains("login")) {
                    continue;
                }
                if (m.getParameterTypes().length != 3) {
                    continue;
                }
                boolean allStrings = true;
                for (Class<?> p : m.getParameterTypes()) {
                    if (p != String.class) {
                        allStrings = false;
                        break;
                    }
                }
                if (!allStrings) {
                    continue;
                }
                target = m;
                Log.i(TAG, "forcelogin: using " + n);
                break;
            }
            if (target == null) {
                Log.i(TAG, "forcelogin: no login bridge found");
                return false;
            }
            target.setAccessible(true);
            target.invoke(null, "1", "玩家", "1");
            Log.i(TAG, "forcelogin: invoked " + target.getName());
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "forcelogin failed: " + t);
            return false;
        }
    }
}
