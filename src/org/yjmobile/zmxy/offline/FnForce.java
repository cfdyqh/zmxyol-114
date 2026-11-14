package org.yjmobile.zmxy.offline;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 蜂鸟（com.ssjj.fnsdk）登录兜底。
 *
 * 11.4.0 的登录链是：
 *   native -> YJMobileJNIHelper.loginHummingbirdSDK()
 *          -> SsjjFNSDK.login(activity)
 *          -> SsjjFNUserListener.onLoginSucceed(SsjjFNUser)
 *          -> YJMobileJNIHelper.hummingbirdLoginFinished() -> native 发 login 请求
 *
 * 离线没有渠道服可登，所以这里直接反射构造游戏自己的监听器
 * （org.yjmobile.lib.llL1ii，包内可见，构造器无参）并替 SDK 回调
 * onLoginSucceed；同时把 helper 里的静态标志位、uid、名字都写上，
 * 免得 native 走 *_loginQuietly 分支时判定"未登录"。
 *
 * 只在本地服还没收到 login 请求时动手，能正常登录的机器是 no-op。
 */
final class FnForce implements Runnable {
    private static final String TAG = "YJOffline";

    private static volatile boolean sDone;

    private FnForce() {
    }

    static void schedule() {
        Thread t = new Thread(new FnForce(), "yjfnforce");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "fnforce: watchdog started");
    }

    @Override
    public void run() {
        try {
            TprtBoot.init(null);
        } catch (Throwable t) {
            Log.i(TAG, "tprt boot failed: " + t);
        }

        for (int i = 0; i < 240; i++) {
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException ignored) {
                return;
            }
            if (sDone) {
                return;
            }
            if (LocalServer.sawLogin()) {
                sDone = true;
                Log.i(TAG, "fnforce: login already happened, stop");
                return;
            }
            if (i < 4) {
                continue;                    // 给客户端约 6 秒自己走（native 库要加载完）
            }
            try {
                if (poke()) {
                    Log.i(TAG, "fnforce: forced login callback #" + i);
                }
            } catch (Throwable t) {
                if (i % 10 == 0) {
                    Log.i(TAG, "fnforce: poke failed: " + t);
                }
            }
        }
        Log.i(TAG, "fnforce: gave up");
    }

    private static volatile boolean sFired;

    private static boolean poke() throws Exception {
        Class<?> helper = Class.forName("org.yjmobile.lib.YJMobileJNIHelper");

        // 初始化完成标志 / 已登录标志（旧版字段名，存在就写，不存在忽略）
        setStaticBoolean(helper, "f1438L111", true);
        setStaticBoolean(helper, "f1466", true);

        invokeIfPresent(helper, "setHummingbirdSDKLogined", boolean.class, Boolean.TRUE);
        invokeIfPresent(helper, "setSDKUserID", String.class, "4399zmxyx");
        invokeIfPresent(helper, "setSDKUserName", String.class, "离线玩家");
        invokeIfPresent(helper, "setSDKExt", String.class, "");

        /* 11.4.0 真正的入口：YJMobileJNIHelper.hummingbirdLoginFinished()（无参）
           -> nativeHummingbirdLoginFinished()（dex 原型 "V ()"，0xe6d060）。
           旧代码反射 8.0 的混淆监听器类 org.yjmobile.lib.llL1ii，
           但 11.4.0 里那个类的名字是 ll丨L1ii（带 CJK 字符 U+4E28），
           Class.forName 一直抛 ClassNotFoundException，登录永远不触发。 */
        if (!sFired) {
            sFired = true;
            invokeNoArg(helper, "hummingbirdLoginFinished");
            Log.i(TAG, "fnforce: hummingbirdLoginFinished() invoked");
        }
        return true;
    }

    private static void invokeNoArg(Class<?> c, String name) {
        try {
            Method m = c.getMethod(name);
            m.invoke(null);
        } catch (Throwable t) {
            Log.i(TAG, "fnforce: " + name + " failed: " + t);
        }
    }

    private static void setStaticBoolean(Class<?> c, String name, boolean v) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            f.setBoolean(null, v);
        } catch (Throwable ignored) {
        }
    }

    private static void invokeIfPresent(Class<?> c, String name, Class<?> arg, Object v) {
        try {
            Method m = c.getMethod(name, arg);
            m.invoke(null, v);
        } catch (Throwable ignored) {
        }
    }

    private static void setField(Object o, String name, String v) {
        try {
            Field f = o.getClass().getField(name);
            f.set(o, v);
        } catch (Throwable ignored) {
        }
    }
}
