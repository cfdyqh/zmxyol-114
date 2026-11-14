# -*- coding: utf-8 -*-
"""再补一步：清理「从没进过游戏（没有存档文件）」的角色占位记录。"""
import io

P = r"v800\java\org\yjmobile\zmxy\offline\LocalServer.java"

OLD = '''            if (changed) {
                writeRoles(roles);
                trace("ensureRoles: roles synced from saves");
            }
            forceMaxRoleLevel(roles, true);'''

NEW = '''            // ⑤ 清理「从没进过游戏」的角色占位：旧版每次登录会自动铺 6 个职业占位
            //    （孙悟空/唐僧/猪八戒/沙僧/猫妖/异界王子），没玩过的那些没有存档文件，
            //    留在列表里只会让多存档看起来一团乱。当前角色和上次登录的角色永远保留。
            try {
                org.json.JSONArray keep = new org.json.JSONArray();
                int last = lastRid();
                for (int i = 0; i < roles.length(); i++) {
                    org.json.JSONObject r = roles.optJSONObject(i);
                    if (r == null) {
                        continue;
                    }
                    int rid = r.optInt("rid", 0);
                    boolean hasSave = rid > 0 && readSaveObjOf(rid) != null;
                    if (hasSave || rid == last || rid == sCurRid) {
                        keep.put(r);
                    } else {
                        changed = true;
                        trace("ensureRoles: drop unplayed role rid=" + rid);
                    }
                }
                if (keep.length() > 0) {
                    roles = keep;
                }
            } catch (java.lang.Throwable th) {
                trace("ensureRoles prune failed " + th);
            }
            if (changed) {
                writeRoles(roles);
                trace("ensureRoles: roles synced from saves");
            }
            forceMaxRoleLevel(roles, true);'''

src = io.open(P, encoding="utf-8").read()
assert OLD in src, "anchor not found"
src = src.replace(OLD, NEW, 1)
io.open(P, "w", encoding="utf-8", newline="\n").write(src)
print("prune patched")
