package org.yjmobile.zmxy.offline;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 开局公告浮层：原生 Android 对话框，几秒后才允许点「确认」。
 *
 * 游戏自己的公告面板是 cocos 的，按钮时序改不了，所以这里用 Java 自己弹一个。
 * 顶层类 + 独立的 LifecycleCallbacks 类 —— d8 对匿名内部类有 EnclosingMethod 的坑。
 */
final class StartDialog {
    private static final String TAG = "YJOffline";
    private static final int DELAY_SEC = 20;

    private StartDialog() {
    }

    /** 挂上生命周期回调，等第一个 Activity resume 后弹窗。 */
    static void install(Context ctx) {
        try {
            Application app = (Application) ctx.getApplicationContext();
            app.registerActivityLifecycleCallbacks(new Cb());
            Log.i(TAG, "StartDialog installed");
        } catch (Throwable t) {
            Log.i(TAG, "StartDialog install failed: " + t);
        }
    }

    private static final class Cb implements Application.ActivityLifecycleCallbacks {
        private boolean shown;

        @Override
        public void onActivityCreated(Activity a, Bundle b) {
            if (shown) {
                return;
            }
            shown = true;
            try {
                a.runOnUiThread(new ShowTask(a));
            } catch (Throwable t) {
                Log.i(TAG, "show failed: " + t);
            }
        }

        @Override
        public void onActivityStarted(Activity a) {
        }

        @Override
        public void onActivityResumed(final Activity a) {
        }

        @Override
        public void onActivityPaused(Activity a) {
        }

        @Override
        public void onActivityStopped(Activity a) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity a, Bundle b) {
        }

        @Override
        public void onActivityDestroyed(Activity a) {
        }
    }

    private static final class ShowTask implements Runnable {
        private final Activity act;

        ShowTask(Activity a) {
            this.act = a;
        }

        @Override
        public void run() {
            try {
                final Dialog d = new Dialog(act);
                d.setTitle("提示");
                LinearLayout root = new LinearLayout(act);
                root.setOrientation(LinearLayout.VERTICAL);
                root.setPadding(40, 30, 40, 30);

                TextView tv = new TextView(act);
                tv.setText(LocalServer.noticeText());
                tv.setTextSize(15f);
                tv.setTextColor(Color.BLACK);
                ScrollView sv = new ScrollView(act);
                sv.addView(tv, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 600));
                root.addView(sv);

                final Button ok = new Button(act);
                ok.setEnabled(false);
                ok.setText("我已阅读并同意（20）");
                ok.setGravity(Gravity.CENTER);
                root.addView(ok, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));


                d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
                d.setContentView(root);
                d.setCancelable(false);
                d.show();
                // 铺满整个屏幕 + 不透明背景（挡住后面的游戏画面）
                android.view.Window w = d.getWindow();
                if (w != null) {
                    w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT);
                    w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF1B1B1B));
                    w.setDimAmount(1.0f);
                }
                root.setBackgroundColor(0xFF1B1B1B);
                tv.setTextColor(0xFFE8E8E8);

                ok.setOnClickListener(new OkTask(d));

                // 兜底：模拟器上 adb 注入的点击到不了这个 Dialog 窗口，
                // 20 秒倒计时一结束就自动关掉直接进游戏（提前点「确认」也行）。
                ok.postDelayed(new Runnable() {
                    public void run() {
                        try {
                            if (d.isShowing()) {
                                d.dismiss();
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }, 20500L);
                // 关键：show() 之后再禁用一次，并用按钮自己的 postDelayed 逐秒
                ok.setEnabled(false);
                ok.setClickable(false);
                ok.setText("我已阅读并同意（20）");
                ok.postDelayed(new CountDown(ok, 19), 1000L);
            } catch (Throwable t) {
                Log.i(TAG, "dialog failed: " + t);
            }
        }
    }

    private static final class OkTask implements android.view.View.OnClickListener {
        private final Dialog d;

        OkTask(Dialog d) {
            this.d = d;
        }

        @Override
        public void onClick(android.view.View v) {
            try {
                LocalServer.markConfirmed();
                d.dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class CountDown implements Runnable {
        private final Button btn;
        private final int left;

        CountDown(Button b, int left) {
            this.btn = b;
            this.left = left;
        }

        @Override
        public void run() {
            try {
                if (left <= 0) {
                    btn.setText("确认");
                    btn.setEnabled(true);
                } else {
                    btn.setText("请阅读（" + left + "）");
                    new Handler(Looper.getMainLooper()).postDelayed(new CountDown(btn, left - 1), 1000);
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
