# -*- coding: utf-8 -*-
"""8.0 多存档：照 11.4 的做法重写 ensurePresetRoles（不再自动铺 6 个职业角色），
补 per-rid 存档/档案读写，新建角色缺档时按同职业复制。"""
import io

P = r"v800\java\org\yjmobile\zmxy\offline\LocalServer.java"

HELPERS = r'''    private static java.io.File saveFileOf(int rid) {
        return new java.io.File(dir(), rid > GUILD_ID ? "savedata_" + rid + ".json" : "savedata.json");
    }

    private static java.io.File profileFileOf(int rid) {
        return new java.io.File(dir(), rid > GUILD_ID ? "profile_" + rid + ".json" : "profile.json");
    }

    private static org.json.JSONObject readSaveObjOf(int rid) {
        try {
            java.io.File f = saveFileOf(rid);
            if (!f.exists() || f.length() < 2) {
                return null;
            }
            return new org.json.JSONObject(new java.lang.String(readAll(f), "UTF-8"));
        } catch (java.lang.Throwable th) {
            return null;
        }
    }

    private static void writeSaveOf(int rid, java.lang.String json) {
        try {
            java.io.File f = saveFileOf(rid);
            java.io.File tmp = new java.io.File(dir(), "savedata_" + rid + ".tmp");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
            fo.write(org.yjmobile.zmxy.offline.Des.bytes(json));
            fo.close();
            if (f.exists()) {
                f.delete();
            }
            tmp.renameTo(f);
        } catch (java.lang.Throwable th) {
            trace("writeSaveOf failed rid=" + rid + " " + th);
        }
    }

    /**
     * 多存档的角色表维护（照 11.4 的 ensurePresetRoles 改）。
     *
     * <p>8.0 原来的写法是**每次登录都把 6 个职业各补一个角色**（孙悟空/唐僧/猪八戒/
     * 沙僧/猫妖/异界王子），于是：
     * <ul>
     *   <li>玩家自己新建的角色被这些自动角色淹没，列表里一大堆没玩过的号；</li>
     *   <li>新建角色的 rid = max+1，跟自动角色的 rid 混在一起；</li>
     *   <li>「新建的存档不保存、只剩孙悟空那一个档」——11.4 也踩过同一个坑，
     *       注释里写着「以前这里为了绕开多角色崩把所有角色删到只剩 1 个，结果玩家
     *       在游戏里新建的角色一退出登录就被删掉」。</li>
     * </ul>
     *
     * <p>现在只做四件事：①首次进游戏（roles 为空）种一个默认角色；
     * ②service<=0 归到 1 服；③每个角色补一份 profile；
     * ④roles.json 里的等级以该角色自己的存档为准（否则客户端会拿列表里的等级
     * 把存档覆盖成 1 级）。**不再自动增加任何角色。**
     */
    private static synchronized void ensurePresetRoles() {
        try {
            org.json.JSONArray roles = readRoles();
            if (roles == null) {
                roles = new org.json.JSONArray();
            }
            if (roles.length() == 0) {
                roles.put(roleRecord(GUILD_ID, GUILD_ID, PRESET_NAMES[0], GUILD_ID, 0, GUILD_ID));
                writeRoles(roles);
                trace("ensureRoles: seeded default role rid=1 (" + PRESET_NAMES[0] + ")");
                return;
            }
            boolean changed = false;
            for (int i = 0; i < roles.length(); i++) {
                org.json.JSONObject r = roles.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                if (r.optInt("service", GUILD_ID) <= 0) {
                    r.put("service", GUILD_ID);
                    changed = true;
                }
                int rid = r.optInt("rid", 0);
                if (rid <= 0) {
                    continue;
                }
                // ③ profile 必须是每个角色自己的那份
                if (!profileFileOf(rid).exists()) {
                    try {
                        org.json.JSONObject p = new org.json.JSONObject();
                        p.put("rid", rid);
                        p.put("roleid", r.optInt("roleid", GUILD_ID));
                        p.put("username", r.optString("username", "玩家"));
                        p.put("rolelv", r.optInt("rolelv", GUILD_ID));
                        p.put("roleexp", r.optInt("roleexp", 0));
                        p.put("service", r.optInt("service", GUILD_ID));
                        java.io.FileOutputStream fo = new java.io.FileOutputStream(profileFileOf(rid));
                        fo.write(org.yjmobile.zmxy.offline.Des.bytes(p.toString()));
                        fo.close();
                        trace("ensureRoles: seeded profile rid=" + rid);
                    } catch (java.lang.Throwable th) {
                    }
                }
                // ④ 等级以存档为准
                org.json.JSONObject sd = readSaveObjOf(rid);
                if (sd == null) {
                    // 没有存档：不拿别的职业的档去复制（职业不同会「存档数据已损坏」），
                    // 让客户端自己建；下面 createrole 那步会负责补同职业模板。
                    continue;
                }
                try {
                    org.json.JSONObject rd = sd.optJSONObject("roledata");
                    org.json.JSONObject role = rd == null ? null : rd.optJSONObject("role");
                    int lv = role == null ? 0 : role.optInt("rolelv", 0);
                    if (lv > 0 && r.optInt("rolelv", -1) != lv) {
                        r.put("rolelv", lv);
                        r.put("rolelevel", lv);
                        changed = true;
                    }
                } catch (java.lang.Throwable th) {
                }
            }
            if (changed) {
                writeRoles(roles);
                trace("ensureRoles: roles synced from saves");
            }
            forceMaxRoleLevel(roles, true);
        } catch (java.lang.Throwable th) {
            trace("ensurePresetRoles failed " + th);
        }
    }

'''

src = io.open(P, encoding="utf-8").read()

a = src.index("    private static synchronized void ensurePresetRoles() {")
marker = "    private static boolean forceMaxRoleLevel("
b = src.index(marker, a)
src = src[:a] + HELPERS + src[b:]

io.open(P, "w", encoding="utf-8", newline="\n").write(src)
print("multisave2 patched")
