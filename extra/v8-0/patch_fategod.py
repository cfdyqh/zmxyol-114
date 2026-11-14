# -*- coding: utf-8 -*-
"""8.0 本命神修复（参考 11.4）：上限 90 级 = exp 8099 / degree 89，并加晋阶处理器。
   - FATE_GOD_LV 70 → 90，FATE_GOD_EXP 999999 → 8099（客户端 lv = sqrt(exp+1)，90²-1=8099）
   - force 块补 degree=89、skills 默认三技能
   - 新增 sanitizeFateGod（登录/上传都夹紧，防客户端把 exp 传回 999999）
   - 新增 fillFateGod：action 以 fategod 开头时按 11.4 的规则晋阶/升级
"""
import io

P = r"v800\java\org\yjmobile\zmxy\offline\LocalServer.java"

FILL = r'''    /**
     * 本命神（fategod）升级/晋阶 —— 照 11.4 的 fillFateGod。
     *
     * <p>等级由 exp 反推：`lv = floor(sqrt(exp+1))`，所以 90 级对应 exp = 90*90-1 = 8099，
     * 客户端按 `fategod_raise_degree.sdb` 的 grade 1..90 取属性，`degree` 只能 0..89。
     * 8.0 原来直接把 exp 写死 999999（≈1000 级），越界的等级会让本命神面板/进关卡出问题。
     */
    private static void fillFateGod(java.lang.String action, org.json.JSONObject req,
            org.json.JSONObject r) {
        try {
            org.json.JSONObject store = readSaveObj();
            if (store == null) {
                store = new org.json.JSONObject();
                store.put("point", 0);
            }
            org.json.JSONObject rd = store.optJSONObject("roledata");
            if (rd == null) {
                rd = new org.json.JSONObject();
                store.put("roledata", rd);
            }
            org.json.JSONObject role = rd.optJSONObject("role");
            if (role == null) {
                role = new org.json.JSONObject();
                rd.put("role", role);
            }
            org.json.JSONObject fg = role.optJSONObject("fategod");
            if (fg == null) {
                fg = new org.json.JSONObject();
                role.put("fategod", fg);
            }
            if (fg.optInt("id", 0) <= 0) {
                fg.put("id", 509200);
            }
            org.json.JSONObject bag = store.optJSONObject("bag");
            org.json.JSONArray items = bag == null ? null : bag.optJSONArray("item");
            int used = 0;
            java.lang.String fail = null;
            boolean raiseDegree = action.contains("raisedegree") || action.contains("raise_stage")
                    || action.contains("raisestage") || action.contains("degree")
                    || action.contains("stage");
            if (raiseDegree) {
                // 晋阶：命火 503371（面板显示 11 个/次），degree+1，上限 89
                int need = req.optInt("num", req.optInt("count", 11));
                if (need <= 0) {
                    need = 11;
                }
                int have = 0;
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        org.json.JSONObject it = items.optJSONObject(i);
                        if (it != null && it.optInt("A2", 0) == 503371) {
                            have += it.optInt("A4", 0);
                        }
                    }
                }
                if (have >= need) {
                    takeStack(items, 503371, need, null);
                    used = need;
                    fg.put("degree", java.lang.Math.min(89,
                            java.lang.Math.max(0, fg.optInt("degree", 0)) + 1));
                    trace("fategod 晋阶 -> degree=" + fg.optInt("degree", 0) + " 花命火 " + used);
                } else {
                    fg.put("degree", java.lang.Math.min(89, java.lang.Math.max(0, fg.optInt("degree", 0))));
                    fail = "命火不足，需要 " + need + " 个";
                }
            } else {
                // 升级：exp 到 8099（=90 级）为止；一次一级或十级
                int exp = java.lang.Math.max(0, java.lang.Math.min(8099, fg.optInt("exp", 0)));
                int lv = (int) java.lang.Math.sqrt(exp + 1);
                if (lv < 90) {
                    int type = req.optInt("type", 0);
                    lv = java.lang.Math.min(90, type == 1 ? lv + 1 : lv + 10);
                    exp = java.lang.Math.min(8099, lv * lv - 1);
                }
                fg.put("exp", exp);
            }
            sanitizeFateGod(store);
            writeSave(store.toString());
            r.put("fategod", fg);
            r.put("fate_god", fg);
            r.put("exp", fg.optInt("exp", 0));
            r.put("degree", fg.optInt("degree", 0));
            r.put("cost", used);
            r.put("goods", new org.json.JSONArray());
            r.put("state", fail == null ? 0 : 1);
            r.put("msg", fail == null ? "" : fail);
            trace("fategod " + action + " degree=" + fg.optInt("degree", 0)
                    + " exp=" + fg.optInt("exp", 0) + (fail == null ? "" : " (" + fail + ")"));
        } catch (java.lang.Throwable th) {
            trace("fillFateGod failed " + th);
        }
    }

    /** 本命神 exp / degree 夹紧（11.4 同款）：exp ≤ 8099（90 级）、degree ≤ 89（grade 1..90）。 */
    private static void sanitizeFateGod(org.json.JSONObject sd) {
        try {
            org.json.JSONObject rd = sd.optJSONObject("roledata");
            org.json.JSONObject role = rd == null ? null : rd.optJSONObject("role");
            if (role == null) {
                return;
            }
            org.json.JSONObject fg = role.optJSONObject("fategod");
            if (fg == null) {
                return;
            }
            if (fg.optInt("id", 0) <= 0) {
                fg.put("id", 509200);
            }
            fg.put("exp", java.lang.Math.max(0, java.lang.Math.min(8099, fg.optInt("exp", 0))));
            fg.put("degree", java.lang.Math.max(0, java.lang.Math.min(89, fg.optInt("degree", 0))));
            fg.put("lv", java.lang.Math.min(90, java.lang.Math.max(1,
                    (int) java.lang.Math.sqrt(fg.optInt("exp", 0) + 1))));
            fg.put("level", fg.optInt("lv", 90));
            if (fg.optJSONArray("skills") == null || fg.optJSONArray("skills").length() == 0) {
                org.json.JSONArray sk = new org.json.JSONArray();
                sk.put(1220);
                sk.put(1120);
                sk.put(1230);
                fg.put("skills", sk);
            }
        } catch (java.lang.Throwable th) {
            trace("sanitizeFateGod failed " + th);
        }
    }

'''

src = io.open(P, encoding="utf-8").read()

# 1) 常量：70 → 90；999999 → 8099
old = "    private static final int FATE_GOD_EXP = 999999;\n    private static final int FATE_GOD_LV = 70;"
new = ("    /** 本命神 90 级上限：客户端 lv = floor(sqrt(exp+1))，90*90-1 = 8099。 */\n"
       "    private static final int FATE_GOD_EXP = 8099;\n"
       "    private static final int FATE_GOD_LV = 90;\n"
       "    /** fategod_raise_degree.sdb 的 grade 是 1..90，degree 只能 0..89。 */\n"
       "    private static final int FATE_GOD_DEGREE = 89;")
assert old in src, "const anchor"
src = src.replace(old, new, 1)

# 2) force 块：补 degree / skills
old2 = '''                            jSONObjectOptJSONObject8.put("lv", FATE_GOD_LV);
                            jSONObjectOptJSONObject8.put("level", FATE_GOD_LV);
                            jSONObjectOptJSONObject8.put("exp", FATE_GOD_EXP);'''
new2 = '''                            jSONObjectOptJSONObject8.put("lv", FATE_GOD_LV);
                            jSONObjectOptJSONObject8.put("level", FATE_GOD_LV);
                            jSONObjectOptJSONObject8.put("exp", FATE_GOD_EXP);
                            jSONObjectOptJSONObject8.put("degree", FATE_GOD_DEGREE);'''
assert old2 in src, "force anchor"
src = src.replace(old2, new2, 1)

# 3) buildSavedataBlock 包装里加 sanitize
old3 = '''                ensureNoblestMaterials(sd);
                writeSave(sd.toString());'''
new3 = '''                ensureNoblestMaterials(sd);
                sanitizeFateGod(sd);
                writeSave(sd.toString());'''
assert old3 in src, "wrapper anchor"
src = src.replace(old3, new3, 1)

# 4) storeSaveUpload 里也夹紧客户端上传的 exp/degree
old4 = "    private static synchronized void storeSaveUpload(org.json.JSONObject jSONObject) {"
new4 = ("    private static synchronized void storeSaveUpload(org.json.JSONObject jSONObject) {\n"
        "        // 客户端会把本命神 exp 原样传回来（以前被写死成 999999 = 1000 级），先夹紧再落盘\n"
        "        sanitizeFateGod(jSONObject);")
assert old4 in src, "upload anchor"
src = src.replace(old4, new4, 1)

# 5) 插入 fillFateGod + sanitizeFateGod（放在 ensureNoblestMaterials 之前）
anchor = "    /** 至尊/传承用的材料："
i = src.index(anchor)
src = src[:i] + FILL + src[i:]

# 6) dispatch 里挂上 fategod 分支
old6 = '''            } else if ("handleaction".equals(strOptString)) {'''
new6 = '''            } else if (strOptString.startsWith("fategod") || "upfategod".equals(strOptString)
                    || "fategodlevelup".equals(strOptString)
                    || "fategodraisedegree".equals(strOptString)
                    || strOptString.toLowerCase().contains("fategod")) {
                fillFateGod(strOptString, jSONObject, jSONObject4);
            } else if ("handleaction".equals(strOptString)) {'''
assert old6 in src, "dispatch anchor"
src = src.replace(old6, new6, 1)

io.open(P, "w", encoding="utf-8", newline="\n").write(src)
print("fategod patched")
