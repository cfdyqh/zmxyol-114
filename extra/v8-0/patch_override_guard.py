# -*- coding: utf-8 -*-
"""兜底：即使 /sdcard/yj/login*.json 覆盖文件存在，rolelist 也用服务端实时算的。"""
import io

P = r"v800\java\org\yjmobile\zmxy\offline\LocalServer.java"

PATCH = r'''    /**
     * 兜底：覆盖文件（`/sdcard/yj/login*.json`）里的 `rolelist` 换成服务端实时算的。
     *
     * <p>调试用的覆盖文件是**静态快照**，里面写死了当时的角色列表；一旦残留，
     * 玩家在服务端新建/预置的角色客户端就永远看不到（实测：
     * `OVERRIDE login` 后面的应答只有 1 个角色，而服务端 roles.json 里已有 6 个）。
     */
    private static java.lang.String patchRoleList(java.lang.String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            o.put("rolelist", roleListAll());
            if (sCurRid > 0) {
                o.put("lastrid", java.lang.String.valueOf(sCurRid));
                o.put("rid", java.lang.String.valueOf(sCurRid));
            }
            return o.toString();
        } catch (java.lang.Throwable th) {
            trace("patchRoleList failed " + th);
            return json;
        }
    }

'''

OLD = '''                trace("OVERRIDE " + strOptString);
                return new java.lang.String(bArr, "UTF-8");'''

NEW = '''                trace("OVERRIDE " + strOptString);
                java.lang.String over = new java.lang.String(bArr, "UTF-8");
                if (isLogin(strOptString) || strOptString.startsWith("rolelist")) {
                    // 覆盖文件是静态快照，角色列表必须用服务端实时数据
                    ensurePresetRoles();
                    over = patchRoleList(over);
                }
                return over;'''

src = io.open(P, encoding="utf-8").read()
assert OLD in src, "override anchor"
src = src.replace(OLD, NEW, 1)

# 在 isLogin 定义前插入 patchRoleList
anchor = "    private static boolean isLogin("
i = src.index(anchor)
src = src[:i] + PATCH + src[i:]

io.open(P, "w", encoding="utf-8", newline="\n").write(src)
print("override guard patched")
