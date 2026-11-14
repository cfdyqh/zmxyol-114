package org.yjmobile.zmxy.offline;

/**
 * 占位 ContentProvider：manifest 里把原来的 android.support.v4.content.FileProvider
 * 改名成本类（AXML 字符串池等长替换），进程启动时最先跑 onCreate。
 */
public class Provider extends android.content.ContentProvider {
    @Override
    public boolean onCreate() {
        try {
            Boot.start(getContext());
        } catch (Throwable t) {
            android.util.Log.e("YJOffline", "provider boot failed", t);
        }
        return true;
    }

    @Override
    public android.database.Cursor query(android.net.Uri uri, String[] projection, String selection,
                                         String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(android.net.Uri uri) {
        return null;
    }

    @Override
    public android.net.Uri insert(android.net.Uri uri, android.content.ContentValues values) {
        return null;
    }

    @Override
    public int delete(android.net.Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(android.net.Uri uri, android.content.ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
