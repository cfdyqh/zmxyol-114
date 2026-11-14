# -*- coding: utf-8 -*-
"""把 jadx 反编译的 LocalServer.table() 与 buildSavedataBlock 尾部修成可编译版本。"""
import io

P = r"<tmp>/src\sources\org\yjmobile\zmxy\offline\LocalServer.java"

TABLE = '''    private static synchronized org.json.JSONObject table(java.lang.String str) {
        if (sTables.containsKey(str)) {
            return sTables.get(str);
        }
        org.json.JSONObject jSONObject = null;
        try {
            java.lang.String strReplaceAll = new java.lang.String(readAll(sCtx.getAssets().open("database/" + str + ".sdb")), "UTF-8").replaceAll("[\\\\s\\\\u0000]+", "");
            int length = (4 - (strReplaceAll.length() % HA_UPGRADE_QUALITY)) % HA_UPGRADE_QUALITY;
            if (length > 0) {
                java.lang.StringBuilder sb = new java.lang.StringBuilder(strReplaceAll);
                for (int i = 0; i < length; i += GUILD_ID) {
                    sb.append('=');
                }
                strReplaceAll = sb.toString();
            }
            jSONObject = new org.json.JSONObject(new java.lang.String(org.yjmobile.zmxy.offline.Des.decrypt(strReplaceAll.getBytes("US-ASCII")), "UTF-8"));
            trace("table " + str + " rows=" + jSONObject.length());
        } catch (java.lang.Throwable th) {
            trace("table " + str + " failed " + th);
            jSONObject = null;
        }
        sTables.put(str, jSONObject);
        return jSONObject;
    }

'''

with io.open(P, encoding="utf-8") as f:
    src = f.read()

a = src.index("    private static synchronized org.json.JSONObject table(java.lang.String str) {")
b = src.index("    private static void appendStoreItems(", a)
src = src[:a] + TABLE + src[b:]

# buildSavedataBlock 尾部：catch 里没有 return，方法缺 return 语句
bad = '''        } catch (java.lang.Throwable th7) {
            java.lang.Throwable th = th7;
            jSONObject = null;
        }
    }'''
good = '''        } catch (java.lang.Throwable th7) {
            jSONObject = null;
        }
        return jSONObject;
    }'''
if bad in src:
    src = src.replace(bad, good, 1)
    print("fixed tail")
else:
    # 行号已变，按 catch (th7) 定位
    i = src.index("} catch (java.lang.Throwable th7) {")
    j = src.index("\n    }", i) + len("\n    }")
    src = src[:i] + '''} catch (java.lang.Throwable th7) {
            jSONObject = null;
        }
        return jSONObject;
    }''' + src[j:]
    print("fixed tail (by scan)")

with io.open(P, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("ok")
