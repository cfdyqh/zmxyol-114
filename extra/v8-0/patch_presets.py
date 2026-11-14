# -*- coding: utf-8 -*-
"""8.0：预置 6 个职业角色（客户端没有"加号"），每个都带一份独立的初始存档。"""
import io

P = r"v800\java\org\yjmobile\zmxy\offline\LocalServer.java"

NEW = r'''    /**
     * 6 个职业的初始存档（最小可用形状）。
     *
     * <p>8.0 客户端角色页**没有"创建角色"加号**，所以新角色只能由服务端预置。
     * 预置的角色如果没有存档文件，进游戏时服务端下发的是空 savedata，
     * 客户端会当成坏档（或直接卡角色页）——所以这里给每个预置角色现造一份
     * 「刚建号」的存档：只放客户端校验必需的那几块（point / roledata.role.roleid /
     * bag 三个计数 / level.psl / pet 容器），职业相关的技能、本命神留空，
     * 让客户端进游戏时按职业自己初始化（塞别的职业的技能会「存档数据已损坏」）。
     */
    private static org.json.JSONObject initialSave(int classId, int rid) {
        org.json.JSONObject sd = new org.json.JSONObject();
        try {
            sd.put("point", 0);
            org.json.JSONObject rd = new org.json.JSONObject();
            org.json.JSONObject role = new org.json.JSONObject();
            role.put("roleid", classId);
            role.put("rid", rid);
            role.put("rolelv", GUILD_ID);
            role.put("roleexp", 0);
            role.put("roleluck", 15);
            role.put("spellv", 0);
            JSONObject roleskill = new JSONObject();
            roleskill.put("passive_skill", new org.json.JSONArray());
            roleskill.put("passive_skill_lock", 0);
            role.put("roleskill", roleskill);
            rd.put("role", role);
            org.json.JSONObject player = new org.json.JSONObject();
            player.put("roleid", classId);
            player.put("rid", rid);
            player.put("mainlevel", 100001);
            player.put("completeguide", new org.json.JSONArray());
            player.put("completerule", 0);
            player.put("dungeonlevel", new org.json.JSONArray());
            rd.put("player", player);
            // 版本串照抄本机客户端，否则会弹「版本过低」
            rd.put("appversion", "8.0.0");
            rd.put("saveversion", "8.0.0");
            rd.put("creationversion", "8.0.0");
            sd.put("roledata", rd);
            org.json.JSONObject bag = new org.json.JSONObject();
            bag.put("bagsize", 72);
            bag.put("maxglobalitemid", 4400);
            bag.put("spiritcount", 0);
            bag.put("auracount", 0);
            bag.put("exploitscount", 0);
            bag.put("ghostcoincount", 0);
            bag.put("petcoincount", 0);
            bag.put("bag_version", 0);
            bag.put("fashionvisible", 1);
            bag.put("item", new org.json.JSONArray());
            sd.put("bag", bag);
            org.json.JSONObject lv = new org.json.JSONObject();
            lv.put("psl", new org.json.JSONArray());
            lv.put("phfiel", 0);
            sd.put("level", lv);
            org.json.JSONObject pet = new org.json.JSONObject();
            pet.put("bagsize", 8);
            pet.put("petinfo", new org.json.JSONArray());
            pet.put("maxglobalpetid", "4399");
            sd.put("pet", pet);
            sd.put("true_spirit_bag", new org.json.JSONObject("{\"bagsize\":8,\"maxspiritid\":439999,\"list\":[]}"));
        } catch (java.lang.Throwable th) {
            trace("initialSave failed " + th);
        }
        return sd;
    }

    /**
     * 角色表维护：**预置 6 个职业角色**（8.0 客户端没有加号，只能靠这里），
     * 每个角色有自己的 rid / profile / 存档文件；没有存档的预置角色现造一份初始档。
     *
     * <p>以前这里每次登录都按职业补角色但**不给存档**，而且角色列表是把同一份数组
     * 塞进每个服键里（同一个角色重复出现）。现在：①按职业补齐（缺哪个补哪个，
     * 已存在的不动）；②每个角色检查 profile 和存档，缺了补；
     * ③roles.json 的等级以该角色存档为准。
     */
    private static synchronized void ensurePresetRoles() {
        try {
            org.json.JSONArray roles = readRoles();
            if (roles == null) {
                roles = new org.json.JSONArray();
            }
            boolean changed = false;
            for (int c = 0; c < PRESET_CLASSES.length; c++) {
                int classId = PRESET_CLASSES[c];
                boolean exists = false;
                int maxRid = 0;
                for (int i = 0; i < roles.length(); i++) {
                    org.json.JSONObject r = roles.optJSONObject(i);
                    if (r == null) {
                        continue;
                    }
                    if (r.optInt("roleid", 0) == classId) {
                        exists = true;
                    }
                    maxRid = java.lang.Math.max(maxRid, r.optInt("rid", 0));
                }
                if (!exists) {
                    int newRid = maxRid + GUILD_ID;
                    roles.put(roleRecord(newRid, classId, PRESET_NAMES[c], GUILD_ID, 0, GUILD_ID));
                    changed = true;
                    trace("ensureRoles: preset class=" + classId + " rid=" + newRid
                            + " " + PRESET_NAMES[c]);
                }
            }
            // 逐角色：service 归位 / profile / 存档 / 等级
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
                int classId = r.optInt("roleid", GUILD_ID);
                // 存档：预置角色没有就现造一份初始档
                org.json.JSONObject sd = readSaveObjOf(rid);
                if (sd == null) {
                    writeSaveOf(rid, initialSave(classId, rid).toString());
                    trace("ensureRoles: seeded initial save rid=" + rid + " class=" + classId);
                    sd = readSaveObjOf(rid);
                }
                // profile
                if (!profileFileOf(rid).exists()) {
                    try {
                        org.json.JSONObject p = new org.json.JSONObject();
                        p.put("rid", rid);
                        p.put("roleid", classId);
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
                // 等级以存档为准
                if (sd != null) {
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
            }
            if (changed) {
                writeRoles(roles);
                trace("ensureRoles: roles updated, total=" + roles.length());
            }
            forceMaxRoleLevel(roles, true);
        } catch (java.lang.Throwable th) {
            trace("ensurePresetRoles failed " + th);
        }
    }

'''

src = io.open(P, encoding="utf-8").read()
a = src.index("    private static synchronized void ensurePresetRoles() {")
b = src.index("    private static boolean forceMaxRoleLevel(", a)
src = src[:a] + NEW + src[b:]
io.open(P, "w", encoding="utf-8", newline="\n").write(src)
print("presets patched")
