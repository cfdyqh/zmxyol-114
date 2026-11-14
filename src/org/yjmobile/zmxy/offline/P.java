package org.yjmobile.zmxy.offline;

import android.app.Application;
import android.util.Log;

/** 隔离测试用：只做日志，不起本地服、不改内存、不碰 SDK。 */
public class P extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i("YJOffline", "P.onCreate pid=" + android.os.Process.myPid());
    }
}
