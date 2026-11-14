package org.yjmobile.zmxy.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;

/**
 * 左上角悬浮「真灵」按钮。
 *
 * 照 SkipButton 的「进极北」做法：直接挂到 Activity 的 decorView（FrameLayout），
 * 不需要 SYSTEM_ALERT_WINDOW，也不会被 GLSurfaceView 盖住。
 * 位置在「进极北」下面一行。
 */
public final class TrueSpiritButton {

    private static final String TAG = "YJOffline";

    private TrueSpiritButton() {
    }

    public static void install(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        try {
            ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
            if (root == null || root.findViewWithTag("yj-true-spirit") != null) {
                return;
            }
            final Button b = new Button(activity);
            b.setTag("yj-true-spirit");
            b.setText("真灵");
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            b.setAllCaps(false);
            b.setAlpha(0.72f);
            b.setTextColor(Color.WHITE);
            b.setBackgroundColor(Color.argb(0xCC, 0x5A, 0x3A, 0x1A));
            b.setPadding(8, 2, 8, 2);
            b.setOnClickListener(new OpenClick(activity));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = 6;
            lp.topMargin = 80;
            root.addView(b, lp);
            b.bringToFront();
            android.util.Log.i(TAG, "ts button: added to " + activity.getClass().getName());
        } catch (Throwable t) {
            android.util.Log.i(TAG, "ts button: failed " + t);
        }
    }

    static void show(Activity activity) {
        try {
            AlertDialog.Builder b = new AlertDialog.Builder(activity);
            b.setTitle("真灵");
            b.setMessage(LocalServer.trueSpiritSummary());
            b.setPositiveButton("培养", new ActionClick(activity, "train"));
            b.setNeutralButton("出战", new ActionClick(activity, "fight"));
            b.setNegativeButton("休息", new ActionClick(activity, "rest"));
            b.show();
        } catch (Throwable ignored) {
        }
    }

    /** 顶层类，避免 d8 对匿名内部类的 EnclosingMethod 处理问题。 */
    static final class OpenClick implements View.OnClickListener {
        private final Activity activity;

        OpenClick(Activity a) {
            this.activity = a;
        }

        @Override
        public void onClick(View v) {
            TrueSpiritButton.show(activity);
        }
    }

    static final class ActionClick implements DialogInterface.OnClickListener {
        private final Activity activity;
        private final String action;

        ActionClick(Activity a, String act) {
            this.activity = a;
            this.action = act;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            String msg = "操作失败";
            try {
                msg = LocalServer.trueSpiritAction(action);
                android.util.Log.i("YJOffline", "ts action " + action + " -> " + msg);
            } catch (Throwable t) {
                msg = "操作失败: " + t;
                android.util.Log.i("YJOffline", "ts action failed " + t);
            }
            try {
                if (dialog instanceof AlertDialog) {
                    ((AlertDialog) dialog).setMessage(msg + "\n\n" + LocalServer.trueSpiritSummary());
                }
            } catch (Throwable ignored) {
            }
            try {
                android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
    }
}
