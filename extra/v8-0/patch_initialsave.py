# -*- coding: utf-8 -*-
"""初始存档改成「以本机一份真实存档为模板克隆 + 清进度 + 改职业」，
比手搓最小形状更保险（客户端校验的块一个不缺）。"""
import io

P = r"v800\java\org\yjmobile\zmxy\offline\LocalServer.java"

NEW = r'''    private static org.json.JSONObject initialSave(int classId, int rid) {
        // 先找一份「本机已经有的」存档当模板：结构块（roledata.role 的一堆字段、
        // bag/level/pet 的版本串）一个不缺，客户端校验最容易过。
        org.json.JSONObject tpl = null;
        try {
            org.json.JSONArray rs = readRoles();
            for (int i = 0; i < rs.length(); i++) {
                org.json.JSONObject r = rs.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                int trid = r.optInt("rid", 0);
                if (trid <= 0 || trid == rid) {
                    continue;
                }
                org.json.JSONObject c = readSaveObjOf(trid);
                if (c != null && c.length() > 0) {
                    tpl = c;
                    break;
                }
            }
        } catch (java.lang.Throwable th) {
        }
        if (tpl != null) {
            try {
                org.json.JSONObject sd = new org.json.JSONObject(tpl.toString());
                sd.put("point", 0);
                org.json.JSONObject rd = sd.optJSONObject("roledata");
                if (rd == null) {
                    rd = new org.json.JSONObject();
                    sd.put("roledata", rd);
                }
                org.json.JSONObject role = rd.optJSONObject("role");
                if (role == null) {
                    role = new org.json.JSONObject();
                    rd.put("role", role);
                }
                role.put("roleid", classId);
                role.put("rid", rid);
                role.put("rolelv", GUILD_ID);
                role.put("roleexp", 0);
                // 技能/本命神是**职业相关**的，模板是别的职业的话必须清掉，
                // 否则客户端读档时会拿孙悟空/唐僧的技能表去建另一个职业 → 存档已损坏
                role.remove("roleskill");
                role.remove("fategod");
                role.remove("artifactinfo");
                role.remove("fashionassist");
                org.json.JSONObject player = rd.optJSONObject("player");
                if (player == null) {
                    player = new org.json.JSONObject();
                    rd.put("player", player);
                }
                player.put("roleid", classId);
                player.put("rid", rid);
                player.put("completeguide", new org.json.JSONArray());
                player.put("dungeonlevel", new org.json.JSONArray());
                player.put("mainlevel", 100001);
                rd.put("appversion", "8.0.0");
                rd.put("saveversion", "8.0.0");
                rd.put("creationversion", "8.0.0");
                org.json.JSONObject bag = sd.optJSONObject("bag");
                if (bag == null) {
                    bag = new org.json.JSONObject();
                    sd.put("bag", bag);
                }
                bag.put("item", new org.json.JSONArray());
                bag.put("spiritcount", 0);
                bag.put("auracount", 0);
                bag.put("exploitscount", 0);
                bag.put("ghostcoincount", 0);
                if (!bag.has("bagsize")) {
                    bag.put("bagsize", 72);
                }
                if (!bag.has("maxglobalitemid")) {
                    bag.put("maxglobalitemid", 4400);
                }
                org.json.JSONObject lv = sd.optJSONObject("level");
                if (lv == null) {
                    lv = new org.json.JSONObject();
                    sd.put("level", lv);
                }
                lv.put("psl", new org.json.JSONArray());
                org.json.JSONObject pet = sd.optJSONObject("pet");
                if (pet == null) {
                    pet = new org.json.JSONObject();
                    sd.put("pet", pet);
                    pet.put("bagsize", 8);
                    pet.put("petinfo", new org.json.JSONArray());
                    pet.put("maxglobalpetid", "4399");
                } else {
                    pet.put("petinfo", new org.json.JSONArray());
                }
                return sd;
            } catch (java.lang.Throwable th) {
                trace("initialSave clone failed " + th);
            }
        }
        return initialSaveMinimal(classId, rid);
    }

    /** 没有可参照的存档时，手搓一份最小形状。 */
    private static org.json.JSONObject initialSaveMinimal(int classId, int rid) {
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
            rd.put("role", role);
            org.json.JSONObject player = new org.json.JSONObject();
            player.put("roleid", classId);
            player.put("rid", rid);
            player.put("mainlevel", 100001);
            player.put("completeguide", new org.json.JSONArray());
            player.put("completerule", 0);
            player.put("dungeonlevel", new org.json.JSONArray());
            rd.put("player", player);
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
        } catch (java.lang.Throwable th) {
            trace("initialSaveMinimal failed " + th);
        }
        return sd;
    }

'''

src = io.open(P, encoding="utf-8").read()
a = src.index("    private static org.json.JSONObject initialSave(int classId, int rid) {")
b = src.index("    /**\n     * 角色表维护：", a)
src = src[:a] + NEW + src[b:]
io.open(P, "w", encoding="utf-8", newline="\n").write(src)
print("initialSave updated")
