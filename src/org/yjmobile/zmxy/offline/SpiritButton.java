package org.yjmobile.zmxy.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;

import java.io.File;

/**
 * 把主 HUD 的「好友」位置盖成一个「真灵」按钮。
 *
 * <p>11.4 客户端里没有可见的真灵入口（只有 YJBeastBoardLayer 资源和类），
 * 所以这里用 Activity decorView 的兄弟层做一个悬浮按钮：位置对准好友图标，
 * 点开显示本地真灵数据。开关文件：/sdcard/yj/ts_button。
 */
final class SpiritButton implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "YJOffline";
    private static final String FLAG = "/sdcard/yj/ts_button";
    private static volatile boolean sInstalled;

    private SpiritButton() {
    }

    static void install(Application app) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            app.registerActivityLifecycleCallbacks(new SpiritButton());
            Log.i(TAG, "spirit button: lifecycle hook installed");
        } catch (Throwable t) {
            Log.i(TAG, "spirit button: hook failed " + t);
        }
    }

    private static boolean enabled() {
        try {
            return new File(FLAG).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    private void attach(final Activity a) {
        if (!enabled()) {
            return;
        }
        try {
            final ViewGroup root = (ViewGroup) a.getWindow().getDecorView();
            if (root == null || root.findViewWithTag("yj-spirit") != null) {
                return;
            }
            int w = a.getResources().getDisplayMetrics().widthPixels;
            int h = a.getResources().getDisplayMetrics().heightPixels;
            Button b = new Button(a);
            b.setTag("yj-spirit");
            b.setText("真灵");
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            b.setAllCaps(false);
            b.setTextColor(Color.WHITE);
            b.setBackgroundColor(Color.argb(0xE6, 0x1B, 0x5E, 0x8A));
            b.setPadding(4, 2, 4, 2);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    show(a);
                }
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    (int) (w * 0.105f), (int) (h * 0.100f));
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = (int) (w * 0.355f);
            lp.topMargin = (int) (h * 0.040f);
            root.addView(b, lp);
            b.bringToFront();
            Log.i(TAG, "spirit button attached " + w + "x" + h);
        } catch (Throwable t) {
            Log.i(TAG, "spirit button attach failed " + t);
        }
    }

    private static int sIndex;

    private void show(Activity a) {
        String[] candidates = {
                "open_beast_bag", "OPEN_BEAST_BAG", "open_beast", "OPEN_BEAST",
                "show_beast", "SHOW_BEAST", "beast_entrance", "BEAST_ENTRANCE",
                "YJBeastEntranceLayer", "show_beast_entrance"
        };
        String name = candidates[sIndex++ % candidates.length];
        try {
            Class<?> c = Class.forName("org.yjmobile.lib.YJMobileJNIHelper");
            java.lang.reflect.Method m = c.getMethod("nativePostCCNotification", String.class);
            m.invoke(null, name);
            android.widget.Toast.makeText(a, "post " + name, android.widget.Toast.LENGTH_SHORT).show();
            Log.i(TAG, "postCCNotification " + name);
        } catch (Throwable t) {
            Log.i(TAG, "postCCNotification failed " + t);
            android.widget.Toast.makeText(a, "post failed " + t, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    public void onActivityResumed(Activity a) {
        attach(a);
    }

    public void onActivityCreated(Activity a, Bundle b) {
    }

    public void onActivityStarted(Activity a) {
    }

    public void onActivityPaused(Activity a) {
    }

    public void onActivityStopped(Activity a) {
    }

    public void onActivitySaveInstanceState(Activity a, Bundle b) {
    }

    public void onActivityDestroyed(Activity a) {
    }
}
