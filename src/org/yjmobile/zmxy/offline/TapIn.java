package org.yjmobile.zmxy.offline;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.io.FileInputStream;

/**
 * Dev-only remote tap: polls /sdcard/yj/tap.txt ("x y", one tap per distinct
 * content) and replays it into the activity's decor view.  adb's `input tap`
 * is not delivered to this emulator's GLSurfaceView, so the harness dispatches
 * the MotionEvent from inside the process instead.
 */
final class TapIn implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "YJOffline";
    private static final File CMD = new File("/sdcard/yj/tap.txt");

    private volatile Activity activity;
    private String last = "";

    static void install(Application app) {
        TapIn t = new TapIn();
        app.registerActivityLifecycleCallbacks(t);
        t.start();
    }

    private void start() {
        Thread th = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "yjtap");
        th.setDaemon(true);
        th.start();
    }

    private void loop() {
        while (true) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
            String cmd = read();
            if (cmd == null || cmd.length() == 0 || cmd.equals(last)) {
                continue;
            }
            last = cmd;
            if (cmd.trim().equals("dump")) {
                dump(a0());
                continue;
            }
            String[] f = cmd.trim().split("[\\s,]+");
            if (f.length < 2) {
                continue;
            }
            try {
                float x = Float.parseFloat(f[0]);
                float y = Float.parseFloat(f[1]);
                int ms = f.length > 2 ? Integer.parseInt(f[2].trim()) : 40;
                tap(x, y, ms);
            } catch (Throwable t) {
                Log.e(TAG, "tapin: bad cmd " + cmd, t);
            }
        }
    }

    private static String read() {
        try {
            if (!CMD.exists()) {
                return null;
            }
            byte[] b = new byte[(int) CMD.length()];
            FileInputStream in = new FileInputStream(CMD);
            try {
                int n = in.read(b);
                if (n <= 0) {
                    return null;
                }
                return new String(b, 0, n, "UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private Activity a0() {
        return activity;
    }

    private void dump(Activity a) {
        if (a == null) {
            Log.i(TAG, "tapin: no activity to dump");
            return;
        }
        View root = a.getWindow().getDecorView();
        walk(root, 0);
    }

    private void walk(View v, int depth) {
        if (v == null || depth > 12) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(v.getClass().getName()).append(" vis=").append(v.getVisibility())
                .append(" click=").append(v.isClickable())
                .append(" rect=").append(v.getLeft()).append(",").append(v.getTop())
                .append(",").append(v.getWidth()).append("x").append(v.getHeight());
        Log.i(TAG, "view: " + sb);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), depth + 1);
            }
        }
    }

    private void tap(float x, float y, int ms) {
        Activity a = activity;
        if (a == null) {
            Log.i(TAG, "tapin: no activity");
            return;
        }
        View root = a.getWindow().getDecorView();
        View target = findGl(root);
        if (target == null) {
            target = root;
        }
        long t0 = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        MotionEvent up = MotionEvent.obtain(t0, t0 + ms, MotionEvent.ACTION_UP, x, y, 0);
        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try {
            target.dispatchTouchEvent(down);
            target.dispatchTouchEvent(up);
            Log.i(TAG, "tapin: dispatched " + x + "," + y + " to " + target.getClass().getName());
        } catch (Throwable t) {
            Log.e(TAG, "tapin: dispatch failed", t);
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private static View findGl(View v) {
        if (v == null) {
            return null;
        }
        if (v.getClass().getName().contains("GLSurfaceView")) {
            return v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findGl(g.getChildAt(i));
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        this.activity = activity;
    }

    public void onActivityStarted(Activity activity) {
        this.activity = activity;
    }

    public void onActivityResumed(Activity activity) {
        this.activity = activity;
    }

    public void onActivityPaused(Activity activity) {
    }

    public void onActivityStopped(Activity activity) {
    }

    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    public void onActivityDestroyed(Activity activity) {
        if (this.activity == activity) {
            this.activity = null;
        }
    }
}
