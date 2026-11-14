package cn.m4399.operate;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Working replacement for the stub 4399 OperateCenter shipped in the v8 dex.
 *
 * The retail v8.0.0 dex contains a *hollow* cn.m4399.operate SDK (every method
 * body is empty / returns null).  The game still drives it, so on a real device
 * init4399SDKPlatform() NPEs and the main thread dies before the client ever
 * reaches the network.  We put this class in classes.dex (the app dex becomes
 * classes2.dex) so the class loader resolves ours first.
 *
 * Behaviour: "already logged in" platform account, init/login callbacks fired on
 * the main thread exactly like the real SDK.
 */
public class OperateCenter {
    private static final String TAG = "YJOffline";
    private static OperateCenter bf;

    private OperateCenterConfig bk;
    private OnInitGloabListener bl;
    private User currentUser;

    public interface OnInitGloabListener {
        void onInitFinished(boolean z, User user);
        void onSwitchUserAccountFinished(boolean z, User user);
        void onUserAccountLogout(boolean z, int i);
    }

    public interface OnLoginFinishedListener {
        void onLoginFinished(boolean z, int i, User user);
    }

    public interface OnRechargeFinishedListener {
        void onRechargeFinished(boolean z, int i, String str);
    }

    public interface OnQuitGameListener {
        void onQuitGame(boolean z);
    }

    private OperateCenter() {
    }

    private static Handler main() {
        return new Handler(Looper.getMainLooper());
    }

    public static OperateCenter getInstance() {
        synchronized (OperateCenter.class) {
            if (bf == null) {
                bf = new OperateCenter();
            }
        }
        return bf;
    }

    public void setConfig(OperateCenterConfig operateCenterConfig) {
        this.bk = operateCenterConfig;
        Log.i(TAG, "fake4399: setConfig " + operateCenterConfig);
    }

    public OperateCenterConfig getConfig() {
        return bk;
    }

    public User getCurrentAccount() {
        if (currentUser == null) {
            currentUser = new User("4399zmxyx", "4399zmxyx", "4399zmxyx", "4399zmxyx", "1",
                    true, 1, true, "", 0);
        }
        return currentUser;
    }

    public void init(Context context, final OnInitGloabListener listener) {
        Log.i(TAG, "fake4399: init -> post onInitFinished(true)");
        this.bl = listener;
        final User user = getCurrentAccount();
        main().post(new Runnable() {
            public void run() {
                try {
                    if (listener != null) {
                        listener.onInitFinished(true, user);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "fake4399: onInitFinished threw", t);
                }
            }
        });
    }

    public boolean isLogin() {
        return true;
    }

    public void login(Context context, final OnLoginFinishedListener listener) {
        Log.i(TAG, "fake4399: login -> post onLoginFinished(true)");
        final User user = getCurrentAccount();
        main().post(new Runnable() {
            public void run() {
                try {
                    if (listener != null) {
                        listener.onLoginFinished(true, 0, user);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "fake4399: onLoginFinished threw", t);
                }
            }
        });
    }

    public void switchAccount(Context context, final OnLoginFinishedListener listener) {
        login(context, listener);
    }

    public void logout() {
        Log.i(TAG, "fake4399: logout");
        currentUser = null;
        final OnInitGloabListener l = bl;
        main().post(new Runnable() {
            public void run() {
                if (l != null) {
                    l.onUserAccountLogout(true, 0);
                }
            }
        });
    }

    public void recharge(Context context, int i, String str, String str2,
                         OnRechargeFinishedListener onRechargeFinishedListener) {
        Log.i(TAG, "fake4399: recharge ignored");
    }

    public void setServer(String str) {
    }

    public void setSupportExcess(boolean z) {
    }

    public void shouldQuitGame(Context context, final OnQuitGameListener listener) {
        main().post(new Runnable() {
            public void run() {
                if (listener != null) {
                    listener.onQuitGame(true);
                }
            }
        });
    }

    public void showGameForum(Activity activity) {
    }

    public boolean removeCacheAccount(String str) {
        return true;
    }

    public void destroy() {
    }

    public static String getResultMsg(int i) {
        return "ok";
    }

    public static final String getVersion() {
        return "2.15.0.6";
    }

    public final OnInitGloabListener getOnInitGloabListener() {
        return bl;
    }

    public void setTestServer(boolean z) {
    }
}
