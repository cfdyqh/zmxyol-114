package org.yjmobile.zmxy.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

/**
 * 启动即弹的"使用须知"，必须手动点确认。
 *
 * 用 ActivityLifecycleCallbacks 拿 Activity，而不是在 Application 里直接
 * 造 AlertDialog：Application 没有窗口令牌，只能走 overlay 权限；而
 * 生命周期回调本来就在主线程，直接 new AlertDialog.Builder(activity) 最稳。
 *
 * 顶层类、不是匿名内部类：d8 在这套代码里对匿名内部类有 EnclosingMethod 的坑
 * （见 ModWorker 的注释）。
 */
public final class NoticeHook implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "YJOffline";

    private static final String NOTICE =
            "严禁倒卖，倒卖无姆，禁止外传，仅供个人怀旧使用（本产品完全免费，有能力的支持一下），24小时内删除。";

    /** 每个进程只弹一次；进程重启（每次启动游戏）就重置。 */
    private static boolean confirmed = false;
    private static boolean showing = false;
    private static Activity sActivity;

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (confirmed) {
            TrueSpiritButton.install(activity);
            return;
        }
        if (showing) {
            return;
        }
        show(activity);
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }

    private void show(Activity activity) {
        try {
            if (activity == null || activity.isFinishing()) {
                return;
            }
            sActivity = activity;
            showing = true;
            AlertDialog.Builder b =
                    new AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
            b.setTitle("使用须知");
            b.setMessage(NOTICE);
            b.setCancelable(false);
            b.setPositiveButton("我已阅读并同意", null);
            final AlertDialog d = b.create();
            d.setCanceledOnTouchOutside(false);
            d.setOnDismissListener(new MarkDone());
            d.show();
            Log.i(TAG, "notice shown");
            // 5 秒倒计时：期间「我已阅读并同意」置灰不可点
            android.widget.Button ok = d.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
            if (ok != null) {
                ok.setEnabled(false);
                ok.setText("我已阅读并同意（5）");
                new Delay(ok, 4).start();
            }
        } catch (Throwable t) {
            // 弹窗失败也绝不能让游戏起不来
            Log.i(TAG, "notice failed: " + t);
            showing = false;
        }
    }

    /** 逐秒倒计时，归零后放开按钮。顶层类：d8 对匿名内部类有 EnclosingMethod 的坑。 */
    private static final class Delay implements Runnable {
        private final android.widget.Button btn;
        private final int left;

        Delay(android.widget.Button b, int left) {
            this.btn = b;
            this.left = left;
        }

        void start() {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 1000L);
        }

        @Override
        public void run() {
            try {
                if (left <= 0) {
                    btn.setText("我已阅读并同意");
                    btn.setEnabled(true);
                } else {
                    btn.setText("我已阅读并同意（" + left + "）");
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(new Delay(btn, left - 1), 1000L);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 关闭即视为已确认。静态嵌套类，避开 d8 的匿名内部类坑。 */
    private static final class MarkDone implements android.content.DialogInterface.OnDismissListener {
        @Override
        public void onDismiss(android.content.DialogInterface di) {
            confirmed = true;
            showing = false;
            try {
                TrueSpiritButton.install(sActivity);
            } catch (Throwable ignored) {
            }
        }
    }
}
