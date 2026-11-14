package org.yjmobile.zmxy.offline;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 4399 SDK 初始化卡死时的兜底：自己替它回调。
 *
 * 已确认的事实（雷电 / x86 + houdini 上必现）：
 *   - 接口已下线：mobi.4399api.net/openapi/* 现在回 {"code":"99","message":"服务已关闭"}；
 *   - 游戏的 Java 侧把活干完了。org.yjmobile.lib.d 的流程是
 *         OperateCenter.getInstance(ctx) -> init(listener) ->
 *         setOnRecharge/Login/LogoutFinishedListener -> Log.d("sdk init", ...) -> return
 *     init() 自己也只是把任务丢进单线程执行器就返回，所以 Java 侧没有阻塞；
 *     卡的是之后"等回调"的那一段，而 SDK 是把 onInitFinished post 回主线程的，
 *     翻译层上这一步不返回。
 *
 * 这里顺着 SDK 的静态字段拿到 listener，然后在**主线程**直接调
 *     onInitFinished(boolean, String, String, String)
 * 只在"迟迟没有 login 请求"时才动手，能正常进游戏的机器是 no-op。
 */
final class SdkForce implements Runnable {
    private static final String TAG = "YJOffline";

    private static volatile boolean sDone = false;
    private static int sInventory = 0;

    private SdkForce() {
    }

    static void schedule() {
        try {
            Thread t = new Thread(new SdkForce(), "yjsdkforce");
            t.setDaemon(true);
            t.start();
            Log.i(TAG, "sdkforce: watchdog started");
        } catch (Throwable t) {
            Log.i(TAG, "sdkforce schedule failed: " + t);
        }
    }

    @Override
    public void run() {
        // Retry: the SDK object may not exist yet (a cold install extracts assets for
        // tens of seconds), and it must not fire blind.
        for (int i = 0; i < 90; i++) {
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException ignored) {
                return;
            }
            if (sDone) {
                return;
            }
            if (LocalServer.sawLogin()) {
                sDone = true;
                Log.i(TAG, "sdkforce: login already happened, skip");
                return;
            }
            if (i < 5) {
                continue;                       // give the game 10s to do it itself
            }
            Object[] found = findCallback();
            if (found == null) {
                continue;
            }
            sDone = true;
            Log.i(TAG, "sdkforce: listener found after " + ((i + 1) * 2)
                    + "s, posting to main thread");
            try {
                new Handler(Looper.getMainLooper())
                        .post(new Invoke(found[0], (Method) found[1], (Object[]) found[2]));
            } catch (Throwable t) {
                Log.i(TAG, "sdkforce: post failed: " + t);
            }
            return;
        }
        Log.i(TAG, "sdkforce: gave up");
    }

    /**
     * The 4399 SDK renamed its init callback between builds:
     *   3.0.2: OperateCenter$OnInitFinishedListener.onInitFinished(ZString,String,String)
     *   4.1.1: OperateCenter$OnInitGloabListener.onInitFinished(Zcn.m4399.operate.User)
     * so both interfaces are probed, and the invocation arguments are derived from
     * the real parameter types instead of being hard-coded.
     */
    private static final String[] INIT_LISTENERS = {
            "cn.m4399.operate.OperateCenter$OnInitGloabListener",
            "cn.m4399.operate.OperateCenter$OnInitFinishedListener",
    };

    /** @return {listener, method, args} or null when the SDK is not constructed yet */
    private static Object[] findCallback() {
        try {
            Class<?> oc = Class.forName("cn.m4399.operate.OperateCenter");
            Class<?> li = null;
            for (String name : INIT_LISTENERS) {
                try {
                    li = Class.forName(name);
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (li == null) {
                Log.i(TAG, "sdkforce: no listener interface class");
                return null;
            }

            // 1) singleton: a static field of this type, or a static getInstance(...)
            Object instance = null;
            for (java.lang.reflect.Field f : oc.getDeclaredFields()) {
                if (f.getType() == oc && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    instance = f.get(null);
                    if (instance != null) {
                        Log.i(TAG, "sdkforce: instance via static field " + f.getName());
                        break;
                    }
                }
            }
            if (instance == null) {
                for (java.lang.reflect.Method m : oc.getDeclaredMethods()) {
                    if (m.getReturnType() != oc || !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                        continue;
                    }
                    m.setAccessible(true);
                    Object[] a = new Object[m.getParameterTypes().length];
                    for (int i = 0; i < a.length; i++) {
                        a[i] = m.getParameterTypes()[i] == String.class ? "1" : null;
                    }
                    try {
                        instance = m.invoke(null, a);
                    } catch (Throwable ignored) {
                    }
                    if (instance != null) {
                        Log.i(TAG, "sdkforce: instance via " + m.getName() + "()");
                        break;
                    }
                }
            }
            if (instance == null) {
                Log.i(TAG, "sdkforce: no OperateCenter instance yet");
                return null;
            }

            // 2) the listener. In 4.1.1 it is NOT a field of OperateCenter - the
            //    class keeps g:OperateCenterConfig and the callback lives inside it,
            //    so walk two levels.
            Object listener = deepFind(instance, li, 2, "");
            if (listener == null) {
                // 4.1.1 keeps it in the inner task class:
                //   cn.m4399.operate.OperateCenter$1.j : OnInitGloabListener
                // (constructor takes (OperateCenter, Context, OnInitGloabListener)),
                // so look for any OperateCenter$* object reachable from the singleton.
                listener = innerListener(oc, instance, li);
            }
            if (listener == null) {
                Object cfg = null;
                try {
                    java.lang.reflect.Field gf = oc.getDeclaredField("g");
                    gf.setAccessible(true);
                    cfg = gf.get(instance);
                } catch (Throwable ignored) {
                }
                if (cfg != null && sInventory < 3) {
                    sInventory++;
                    StringBuilder sb = new StringBuilder();
                    for (java.lang.reflect.Field f : cfg.getClass().getDeclaredFields()) {
                        sb.append(f.getName()).append(':').append(f.getType().getSimpleName())
                          .append(' ');
                    }
                    Log.i(TAG, "sdkforce: config fields -> " + sb);
                }
            }
            if (sInventory < 2) {
                sInventory++;
                StringBuilder sb = new StringBuilder();
                for (java.lang.reflect.Field f : oc.getDeclaredFields()) {
                    sb.append(f.getName()).append(':')
                      .append(f.getType().getSimpleName())
                      .append(java.lang.reflect.Modifier.isStatic(f.getModifiers()) ? "(s) " : " ");
                }
                Log.i(TAG, "sdkforce: OperateCenter fields -> " + sb);
            }
            if (listener == null) {
                Log.i(TAG, "sdkforce: instance found but no listener of "
                        + li.getName() + " yet");
                return null;
            }

            Method cb = null;
            for (Method m : li.getMethods()) {
                if ("onInitFinished".equals(m.getName())) {
                    cb = m;
                    break;
                }
            }
            if (cb == null) {
                return null;
            }
            Class<?>[] p = cb.getParameterTypes();
            Object[] args = new Object[p.length];
            for (int i = 0; i < p.length; i++) {
                if (p[i] == boolean.class) {
                    args[i] = Boolean.TRUE;
                } else if (p[i] == int.class) {
                    args[i] = Integer.valueOf(0);
                } else if (p[i] == long.class) {
                    args[i] = Long.valueOf(0L);
                } else if (p[i] == String.class) {
                    args[i] = "1";
                } else {
                    // 4.1.1 hands over a cn.m4399.operate.User
                    try {
                        args[i] = p[i].getDeclaredConstructor().newInstance();
                    } catch (Throwable ignored) {
                        args[i] = null;
                    }
                }
            }
            return new Object[]{listener, cb, args};
        } catch (Throwable t) {
            Log.i(TAG, "sdkforce: lookup failed: " + t);
            return null;
        }
    }



    /** 4.1.1: the listener hides in an OperateCenter$N inner object's field. */
    private static Object innerListener(Class<?> oc, Object instance, Class<?> li) {
        try {
            for (java.lang.reflect.Field f : oc.getDeclaredFields()) {
                f.setAccessible(true);
                Object v;
                try {
                    v = java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            ? f.get(null) : f.get(instance);
                } catch (Throwable ignored) {
                    continue;
                }
                if (v == null) {
                    continue;
                }
                String cn = v.getClass().getName();
                if (!cn.startsWith("cn.m4399.operate.OperateCenter$")) {
                    continue;
                }
                Log.i(TAG, "sdkforce: inner candidate " + cn + " via field " + f.getName());
                for (java.lang.reflect.Field g : v.getClass().getDeclaredFields()) {
                    if (g.getType() != li && !li.isAssignableFrom(g.getType())) {
                        continue;
                    }
                    g.setAccessible(true);
                    Object l = null;
                    try {
                        l = java.lang.reflect.Modifier.isStatic(g.getModifiers())
                                ? g.get(null) : g.get(v);
                    } catch (Throwable ignored) {
                    }
                    Log.i(TAG, "sdkforce: inner field " + cn + "." + g.getName() + " = " + l);
                    if (l != null) {
                        return l;
                    }
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "sdkforce: innerListener failed: " + t);
        }
        return null;
    }

    /** Breadth-first field walk: find an object of the wanted type up to depth levels. */
    private static Object deepFind(Object root, Class<?> want, int depth, String path) {
        if (root == null || depth < 0) {
            return null;
        }
        if (want.isAssignableFrom(root.getClass())) {
            Log.i(TAG, "sdkforce: listener at " + path + " type " + root.getClass().getName());
            return root;
        }
        for (java.lang.reflect.Field f : root.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            Object v = null;
            try {
                v = f.get(root);
            } catch (Throwable ignored) {
                continue;
            }
            if (v == null) {
                continue;
            }
            String p = path + "." + f.getName();
            Object hit = deepFind(v, want, depth - 1, p);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** Runs the callback on the main thread, exactly where the SDK would run it. */
    static final class Invoke implements Runnable {
        private final Object listener;
        private final Method cb;
        private final Object[] args;

        Invoke(Object listener, Method cb, Object[] args) {
            this.listener = listener;
            this.cb = cb;
            this.args = args;
        }

        @Override
        public void run() {
            try {
                cb.setAccessible(true);
                cb.invoke(listener, args);
                Log.i(TAG, "sdkforce: onInitFinished invoked on main thread");
            } catch (Throwable t) {
                Log.i(TAG, "sdkforce: invoke failed: " + t);
            }
        }
    }
}
