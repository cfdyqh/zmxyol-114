package org.cocos2dx.lib;

import android.util.Log;

/**
 * Shadow of cocos2d-x's downloader.
 *
 * The v8 app dex has the loopj AsyncHttpClient bodies hollowed out (code
 * extraction), so constructing one throws an AssertionError inside
 * nativeRender and the whole process aborts.  Offline there is nothing to
 * download anyway, so this replacement just logs the request and reports an
 * immediate failure back to the native side.
 */
public class Cocos2dxDownloader {
    private static final String TAG = "YJOffline";
    private int _id;

    public static Cocos2dxDownloader createDownloader(int id, int timeout,
                                                      String tempFileNameSufix,
                                                      int countOfMaxProcessingTasks) {
        Cocos2dxDownloader d = new Cocos2dxDownloader();
        d._id = id;
        Log.i(TAG, "downloader: create id=" + id + " timeout=" + timeout
                + " suffix=" + tempFileNameSufix + " maxTasks=" + countOfMaxProcessingTasks);
        return d;
    }

    public static void createTask(Cocos2dxDownloader downloader, int id, String url,
                                  String tempFile) {
        Log.i(TAG, "downloader: task id=" + id + " url=" + url + " temp=" + tempFile);
        if (downloader == null) {
            return;
        }
        downloader.onFinish(id, -1, "offline: no download", null);
    }

    public static void cancelAllRequests(Cocos2dxDownloader downloader) {
        Log.i(TAG, "downloader: cancelAll");
    }

    public static void setResumingSupport(String key, Boolean value) {
    }

    public void onStart(int id) {
        nativeOnProgress(_id, id, 0L, 0L, 0L);
    }

    public void onFinish(int id, int errCode, String errStr, byte[] data) {
        Log.i(TAG, "downloader: finish id=" + id + " err=" + errCode + " " + errStr);
        try {
            nativeOnFinish(_id, id, errCode, errStr, data);
        } catch (Throwable t) {
            Log.e(TAG, "downloader: nativeOnFinish failed", t);
        }
    }

    void onProgress(int id, long bytesReceived, long totalBytesReceived, long totalBytesExpected) {
        try {
            nativeOnProgress(_id, id, bytesReceived, totalBytesReceived, totalBytesExpected);
        } catch (Throwable t) {
            // ignore
        }
    }

    public void runNextTaskIfExists() {
    }

    public void enqueueTask(Runnable task) {
        if (task != null) {
            task.run();
        }
    }

    private native void nativeOnFinish(int id, int taskId, int errCode, String errStr, byte[] data);

    private native void nativeOnProgress(int id, int taskId, long bytesReceived,
                                         long totalBytesReceived, long totalBytesExpected);
}
