# -*- coding: utf-8 -*-
"""修 SdkForce：run() 的异常覆盖 + 重写被 jadx 搅乱的 innerListener()。"""
import io

P = r"<tmp>/src\sources\org\yjmobile\zmxy\offline\SdkForce.java"

INNER = '''    private static java.lang.Object innerListener(java.lang.Class<?> cls, java.lang.Object obj, java.lang.Class<?> cls2) throws java.lang.IllegalAccessException {
        java.lang.reflect.Field[] declaredFields = cls.getDeclaredFields();
        for (int i = 0; i < declaredFields.length; i++) {
            java.lang.reflect.Field field = declaredFields[i];
            field.setAccessible(true);
            java.lang.Object val = null;
            try {
                val = java.lang.reflect.Modifier.isStatic(field.getModifiers()) ? field.get(null) : field.get(obj);
            } catch (java.lang.Throwable th) {
                val = null;
            }
            if (val == null) {
                continue;
            }
            java.lang.String name = val.getClass().getName();
            if (!name.startsWith("cn.m4399.operate.OperateCenter$")) {
                continue;
            }
            android.util.Log.i(TAG, "sdkforce: inner candidate " + name + " via field " + field.getName());
            java.lang.reflect.Field[] inner = val.getClass().getDeclaredFields();
            for (int j = 0; j < inner.length; j++) {
                java.lang.reflect.Field f2 = inner[j];
                if (f2.getType() != cls2 && !cls2.isAssignableFrom(f2.getType())) {
                    continue;
                }
                f2.setAccessible(true);
                java.lang.Object v2 = null;
                try {
                    v2 = java.lang.reflect.Modifier.isStatic(f2.getModifiers()) ? f2.get(null) : f2.get(val);
                } catch (java.lang.Throwable th2) {
                    v2 = null;
                }
                android.util.Log.i(TAG, "sdkforce: inner field " + name + "." + f2.getName() + " = " + v2);
                if (v2 != null) {
                    return v2;
                }
            }
        }
        return null;
    }

'''

with io.open(P, encoding="utf-8") as f:
    src = f.read()

a = src.index("    private static java.lang.Object innerListener(java.lang.Class<?> cls, java.lang.Object obj, java.lang.Class<?> cls2)")
b = src.index("    private static java.lang.Object deepFind(", a)
src = src[:a] + INNER + src[b:]

old_catch = """            } catch (java.lang.InterruptedException e) {
                return;
            }
        }
        android.util.Log.i(TAG, "sdkforce: gave up");"""
new_catch = """            } catch (java.lang.InterruptedException e) {
                return;
            } catch (java.lang.Throwable th) {
                android.util.Log.i(TAG, "sdkforce: pass failed: " + th);
            }
        }
        android.util.Log.i(TAG, "sdkforce: gave up");"""
assert old_catch in src
src = src.replace(old_catch, new_catch, 1)

with io.open(P, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("ok")
