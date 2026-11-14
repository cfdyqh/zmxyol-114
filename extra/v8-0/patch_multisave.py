# -*- coding: utf-8 -*-
"""8.0 多存档修复：角色列表按服分组 + 登录不再把列表压成当前一个角色。"""
import io

P = r"v800\java\org\yjmobile\zmxy\offline\LocalServer.java"

SIMPLE = r'''    private static org.json.JSONObject simpleRole(org.json.JSONObject jSONObject) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            int lv = jSONObject.optInt("rolelv", GUILD_ID);
            // 8.0 客户端登录模板里这些字段全是字符串（"rid":"1001"…），
            // 这里也发字符串，避免列表解析时类型不一致。
            o.put("rid", java.lang.String.valueOf(jSONObject.optInt("rid", GUILD_ID)));
            o.put("username", jSONObject.optString("username", "玩家"));
            o.put("rolelv", java.lang.String.valueOf(lv));
            o.put("rolelevel", java.lang.String.valueOf(lv));
            o.put("roleid", java.lang.String.valueOf(jSONObject.optInt("roleid", GUILD_ID)));
            o.put("level", java.lang.String.valueOf(GUILD_ID));
            o.put("exp", java.lang.String.valueOf(jSONObject.optInt("roleexp", 0)));
            o.put("vip", "0");
            o.put("wingid", "0");
            o.put("weaponid", "0");
            o.put("suitid", "0");
        } catch (java.lang.Throwable th) {
        }
        return o;
    }

'''

ROLELISTALL = r'''    /**
     * 角色列表：**按 service 分组**，每个服只挂它自己的角色。
     *
     * <p>8.0 原来的写法是把「全部角色」同一个数组塞进 service 1..max 每一个键里 ——
     * 建了第二个角色之后，每个服都会列出全部角色（同一个角色在列表里出现好几次），
     * 切服也切不出「这个服只有这个角色」的效果。这里照 11.4 的做法按 service 分组。
     */
    private static org.json.JSONObject roleListAll() {
        org.json.JSONObject byServer = new org.json.JSONObject();
        try {
            org.json.JSONArray roles = readRoles();
            for (int i = 0; i < roles.length(); i++) {
                org.json.JSONObject r = roles.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                java.lang.String key = java.lang.String.valueOf(
                        java.lang.Math.max(1, r.optInt("service", GUILD_ID)));
                org.json.JSONArray arr = byServer.optJSONArray(key);
                if (arr == null) {
                    arr = new org.json.JSONArray();
                    byServer.put(key, arr);
                }
                arr.put(simpleRole(r));
            }
            // 至少留一个服键，避免客户端拿到空对象
            if (byServer.length() == 0) {
                byServer.put("1", new org.json.JSONArray());
            }
        } catch (java.lang.Throwable th) {
            trace("roleListAll failed " + th);
        }
        return byServer;
    }

'''

src = io.open(P, encoding="utf-8").read()

# 1) 替换 simpleRole
a = src.index("    private static org.json.JSONObject simpleRole(")
b = src.index("    private static org.json.JSONObject roleListAll() {", a)
src = src[:a] + SIMPLE + src[b:]

# 2) 替换 roleListAll
a = src.index("    private static org.json.JSONObject roleListAll() {")
b = src.index("    private static org.json.JSONObject roleListFor(", a)
src = src[:a] + ROLELISTALL + src[b:]

# 3) 登录时不要再把 rolelist 压成「当前一个角色」
old = "                jSONObject2.put(\"rolelist\", roleListFor(strOptString, iOptInt2, iOptInt));"
new = "                jSONObject2.put(\"rolelist\", roleListAll());"
assert old in src, "login rolelist patch anchor"
src = src.replace(old, new, 1)

io.open(P, "w", encoding="utf-8", newline="\n").write(src)
print("multi-save patched")
