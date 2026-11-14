package org.yjmobile.zmxy.offline;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;

/**
 * 悬浮「跳过新手教程」按钮。
 *
 * 直接挂到 GLSurfaceView 的兄弟层（Activity 的 decorView，FrameLayout），
 * 所以不需要 SYSTEM_ALERT_WINDOW（真机上那个权限默认是 ignore）。
 *
 * 开关文件：/sdcard/yj/skipguide
 * 打开后 LocalServer.buildSavedataBlock() 会把 roledata.player.completeguide
 * 填满 900000..900999，客户端认为所有新手引导都做完了，不再弹教学。
 * 改完自动重启进程让新存档生效。
 */
final class SkipButton implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "YJOffline";
    private static final String FLAG = "/sdcard/yj/skipguide";

    private static volatile boolean sInstalled;

    private SkipButton() {
    }

    static void install(Application app) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            app.registerActivityLifecycleCallbacks(new SkipButton());
            Log.i(TAG, "skip button: lifecycle hook installed");
        } catch (Throwable t) {
            Log.i(TAG, "skip button: hook failed " + t);
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
        try {
            final ViewGroup root = (ViewGroup) a.getWindow().getDecorView();
            if (root == null || root.findViewWithTag("yj-skip") != null) {
                return;
            }
            final Button b = new Button(a);
            b.setTag("yj-skip");
            b.setText(enabled() ? "教程:跳过中" : "跳过教程");
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            b.setAllCaps(false);
            b.setAlpha(0.72f);
            b.setTextColor(Color.WHITE);
            b.setBackgroundColor(Color.argb(0xCC, 0x22, 0x22, 0x22));
            b.setPadding(8, 2, 8, 2);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    toggle(a, b);
                }
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = 6;
            lp.topMargin = 6;
            // 「跳过教程」按钮已按用户要求移除（只保留「进极北」）。
            // 想恢复：把下面两行注释去掉即可。
            // root.addView(b, lp);
            // b.bringToFront();

            // 第二个悬浮按钮：「进极北」
            // 点一下把 /sdcard/yj/thule_state 写成 2，服务端下发的
            // thule_task_chain_info.task_chain_state 就是 2（极北之地直接开放，
            // 不用做紫微仙子那条任务链）。重进游戏生效。
            if (root.findViewWithTag("yj-thule") == null) {
                final Button t = new Button(a);
                t.setTag("yj-thule");
                t.setText("进极北");
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                t.setAllCaps(false);
                t.setAlpha(0.72f);
                t.setTextColor(Color.WHITE);
                t.setBackgroundColor(Color.argb(0xCC, 0x0A, 0x4A, 0x8A));
                t.setPadding(8, 2, 8, 2);
                t.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        openThule(a, t);
                    }
                });
                FrameLayout.LayoutParams lp2 = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp2.gravity = Gravity.TOP | Gravity.START;
                lp2.leftMargin = 6;
                lp2.topMargin = 6;
                root.addView(t, lp2);
                t.bringToFront();
            }
            // 真灵按钮也从这里挂：SkipButton 的 attach 是确认会在主界面跑的，
            // NoticeHook 那条生命周期在部分包上没注册，所以之前按钮一直没出现。
            TrueSpiritButton.install(a);
            // 「切换角色」悬浮按钮已移除：多角色改成官服那种「一个服里多个角色」
            // 之后，登录界面左侧就是角色列表，直接点就能选（见 LocalServer.simpleRole
            // 的注释 —— 之前的崩溃是角色条目用了 int 而不是官服那种字符串）。
            // 想恢复旧按钮：把下面这块注释去掉即可。
            /*
            if (root.findViewWithTag("yj-role") == null) {
                final Button r = new Button(a);
                r.setTag("yj-role");
                r.setText("切换角色");
                r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                r.setAllCaps(false);
                r.setAlpha(0.72f);
                r.setTextColor(Color.WHITE);
                r.setBackgroundColor(Color.argb(0xCC, 0x1E, 0x6B, 0x2E));
                r.setPadding(8, 2, 8, 2);
                r.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        cycle(a, r);
                    }
                });
                FrameLayout.LayoutParams lp3 = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp3.gravity = Gravity.TOP | Gravity.START;
                lp3.leftMargin = 6;
                lp3.topMargin = 40;
                root.addView(r, lp3);
                r.bringToFront();
            }
            */
            Log.i(TAG, "skip button: attached to " + a.getClass().getName());
        } catch (Throwable t) {
            Log.i(TAG, "skip button: attach failed " + t);
        }
    }

    /** 切换角色：换一个 rid 再重启进程（各角色存档独立）。 */
    private void cycle(final Activity a, final Button b) {
        try {
            String info = LocalServer.cycleRole();
            if (info == null) {
                b.setText("只有一个角色");
                android.widget.Toast.makeText(a, "只有一个角色，进游戏里的「+」可以新建",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            b.setText("切换到 " + info);
            android.widget.Toast.makeText(a, "切换到 " + info + "，正在重启…",
                    android.widget.Toast.LENGTH_SHORT).show();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Restart(), 1200L);
        } catch (Throwable t) {
            Log.i(TAG, "cycle failed " + t);
        }
    }

    /** 顶层类：d8 对匿名内部类有 EnclosingMethod 的坑。 */
    private static final class Restart implements Runnable {
        @Override
        public void run() {
            try {
                android.os.Process.killProcess(android.os.Process.myPid());
            } catch (Throwable ignored) {
            }
        }
    }

    private void openThule(final Activity a, final Button t) {
        try {
            File f = new File("/sdcard/yj/thule_state");
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write("2".getBytes("UTF-8"));
            fo.close();
            t.setText("极北已开");
            android.widget.Toast.makeText(a, "极北之地已解锁，重进游戏生效", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            t.setText("进极北失败");
            Log.i(TAG, "openThule failed " + e);
        }
    }

    private void toggle(Activity a, Button b) {
        try {
            File f = new File(FLAG);
            boolean now;
            if (f.exists()) {
                f.delete();
                now = false;
            } else {
                f.getParentFile().mkdirs();
                now = f.createNewFile();
            }
            Log.i(TAG, "skip button: skipguide=" + now);
            b.setText(now ? "教程:跳过中" : "跳过教程");
            Toast.makeText(a, now ? "已跳过新手教程，正在重启…" : "已恢复新手教程，正在重启…",
                    Toast.LENGTH_SHORT).show();
            restart(a);
        } catch (Throwable t) {
            Log.i(TAG, "skip button: toggle failed " + t);
        }
    }

    private void restart(Activity a) {
        try {
            Context ctx = a.getApplicationContext();
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent pi = PendingIntent.getActivity(ctx, 1, i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
                if (am != null) {
                    am.set(AlarmManager.RTC, System.currentTimeMillis() + 900L, pi);
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "skip button: restart schedule failed " + t);
        }
        try {
            Thread.sleep(150);
        } catch (InterruptedException ignored) {
        }
        Process.killProcess(Process.myPid());
    }

    // ---- lifecycle ----

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
