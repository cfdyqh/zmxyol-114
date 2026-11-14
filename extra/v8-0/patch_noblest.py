# -*- coding: utf-8 -*-
"""把 11.4 的至尊修复（extremeCnf / 锻升 / 聚炼 / 传承挂接 / CV 语义）移植到 8.0 的 LocalServer。"""
import io

P = r"<tmp>/src\sources\org\yjmobile\zmxy\offline\LocalServer.java"

HELPERS = r'''    // ------------------------------------------------ 至尊（fix80：对齐 11.4 的修法）

    /** 官方 roledata2 顶层 `extremeCnf`（至尊费用/比例规则表，13.0 私服 2026-11-12 的「至尊修复」）。 */
    private static final java.lang.String EXTREME_CNF_JSON =
            "{\"1\":{\"503199\":[30,60,100,150,220,290,380,480,590,700,840,980,1130,1290,1470,"
            + "1650,1850,2060,2280,2500,2750,3000,3260,3530,3820,4110,4420,4740,5070,5400,5760,6120,"
            + "6490,6870,7270],\"-1\":[1,2,3,5,6,5,6,7,8,9,10,10,11,12,13,14,15,16,15,16,18,19,20,21,"
            + "23,24,20,21,22,23,24,26,27,28,29]},\"2\":{\"AF\":[7,14,22,31,40],\"CH\":[7,14,22,31,40],"
            + "\"CJ\":[7,14,22,31,40],\"AC\":[1,3,5,7,10],\"AE\":[4,8,13,19,25],\"CI\":[7,14,22,31,40],"
            + "\"CK\":[7,14,22,31,40],\"CKA\":[2,4,7,11,15],\"CJA\":[2,4,7,11,15],\"CHA\":[5,10,15,20,25],"
            + "\"CYA\":[2,4,7,11,15],\"ACA\":[1,3,5,7,10]},\"3\":{\"-1\":[100,220,440,770,1260]},"
            + "\"4\":{\"-1\":[1]},\"5\":{\"503206\":[0.05,2,6.5,-1],\"503035\":[9.5,2,45.5,451]},"
            + "\"6\":{\"-1\":[100000]},\"7\":{\"-1\":[1]},"
            + "\"8\":{\"503207\":[10,20],\"503227\":[100,200],\"503370\":[0,200]},"
            + "\"9\":{\"503206\":[0.5,2,6.5,-4550],\"503199\":[1,2,5,-8000]},"
            + "\"10\":{\"503208\":[30],\"503209\":[30],\"503210\":[30],\"503370\":[10]}}";

    private static final int ITEM_RONG_LIANDAN = 503206;
    private static final int ITEM_JULIANDAN = 503212;
    private static final int ITEM_YUANLING = 503199;
    private static final int ITEM_XIANQI = 503035;

    private static org.json.JSONObject extremeCnf() {
        try {
            return new org.json.JSONObject(EXTREME_CNF_JSON);
        } catch (java.lang.Throwable th) {
            trace("extremeCnf parse failed " + th);
            return new org.json.JSONObject();
        }
    }

    /** 传承槽配置（官服 roledata2 顶层四张表，50 项版本）。 */
    private static void putSlotStuntConfig(org.json.JSONObject o) {
        try {
            double[] gr = {0.2d, 0.3d, 0.6d, 1.0d, 1.5d};
            org.json.JSONArray gradeRate = new org.json.JSONArray();
            for (int i = 0; i < gr.length; i++) {
                gradeRate.put(gr[i]);
            }
            int[] ip = {200, 300, 600, 1000, 1500};
            org.json.JSONArray initPower = new org.json.JSONArray();
            for (int i = 0; i < ip.length; i++) {
                initPower.put(ip[i]);
            }
            int[] sp = {1000, 2000, 3000, 4000, 5000, 6000};
            org.json.JSONArray starPower = new org.json.JSONArray();
            for (int i = 0; i < sp.length; i++) {
                starPower.put(sp[i]);
            }
            int[] bands = {100, 200, 300, 400, 500, 600, 800};
            int[] counts = {10, 10, 10, 5, 5, 5, 5};
            org.json.JSONArray lvPower = new org.json.JSONArray();
            for (int b = 0; b < bands.length; b++) {
                for (int i = 0; i < counts[b]; i++) {
                    lvPower.put(bands[b]);
                }
            }
            o.put("slot_stunt_grade_power_rate", gradeRate);
            o.put("slot_stunt_init_power", initPower);
            o.put("slot_stunt_star_power", starPower);
            o.put("slot_stunt_lv_power", lvPower);
        } catch (java.lang.Throwable th) {
        }
    }

    /** 锻升消耗（extreme_cnf 规则 5/9）：返回 {id1, n1, id2, n2}。 */
    private static int[] fusionCost(int current) {
        double[] a206 = current < 100
                ? new double[]{0.05d, 2.0d, 6.5d, -1.0d}
                : new double[]{0.5d, 2.0d, 6.5d, -4550.0d};
        double[] a2nd = current < 100
                ? new double[]{9.5d, 2.0d, 45.5d, 451.0d}
                : new double[]{1.0d, 2.0d, 5.0d, -8000.0d};
        int second = current < 100 ? ITEM_XIANQI : ITEM_YUANLING;
        int n1 = fusionAmount(a206, current + 1);
        int n2 = fusionAmount(a2nd, current + 1);
        if (second == ITEM_XIANQI) {
            n2 = (n2 / 10) * 10;
        }
        return new int[]{ITEM_RONG_LIANDAN, java.lang.Math.max(n1, 0), second, java.lang.Math.max(n2, 0)};
    }

    private static int fusionAmount(double[] p, int level) {
        float a = (float) p[0];
        float power = (float) p[1];
        float linear = (float) p[2];
        float offset = (float) p[3];
        float v = (float) java.lang.Math.pow(level, power) * a + linear * (float) level + offset;
        return (int) v;
    }

    private static boolean bagHasStack(org.json.JSONArray items, int id, int need) {
        if (need <= 0) {
            return true;
        }
        org.json.JSONObject it = findStack(items, id);
        return it != null && it.optInt("A4", 0) >= need;
    }

    private static void takeStack(org.json.JSONArray items, int id, int need, org.json.JSONArray cost) throws java.lang.Exception {
        if (need <= 0 || items == null) {
            return;
        }
        org.json.JSONObject it = findStack(items, id);
        if (it == null) {
            return;
        }
        it.put("A4", java.lang.Math.max(0, it.optInt("A4", 0) - need));
        cost.put(costEntry(id, need));
    }

    private static org.json.JSONObject costEntry(int id, int n) throws java.lang.Exception {
        org.json.JSONObject c = new org.json.JSONObject();
        c.put("A2", id);
        c.put("A4", n);
        return c;
    }

    private static int auraCount(org.json.JSONObject store) {
        org.json.JSONObject bag = store == null ? null : store.optJSONObject("bag");
        return bag == null ? 0 : java.lang.Math.max(0, bag.optInt("auracount", 0));
    }

    private static void addAura(org.json.JSONObject store, int delta) throws java.lang.Exception {
        org.json.JSONObject bag = store == null ? null : store.optJSONObject("bag");
        if (bag == null) {
            return;
        }
        bag.put("auracount", java.lang.Math.max(0, bag.optInt("auracount", 0) + delta));
    }

    private static org.json.JSONObject findByA5(org.json.JSONArray items, int uid) {
        if (items == null || uid <= 0) {
            return null;
        }
        for (int i = 0; i < items.length(); i++) {
            org.json.JSONObject it = items.optJSONObject(i);
            if (it != null && it.optInt("A5", 0) == uid) {
                return it;
            }
        }
        return null;
    }

    /** 传承挂接的源装备实例 id（source / source_uid / target.CD / logs[54].M.A5 / material）。 */
    private static int inheritSourceUid(org.json.JSONObject req, org.json.JSONObject target) {
        java.lang.Object src = req.opt("source");
        int uid = 0;
        if (src instanceof org.json.JSONObject) {
            org.json.JSONObject so = (org.json.JSONObject) src;
            uid = so.optInt("A5", 0);
            if (uid <= 0) {
                uid = so.optInt("uid", 0);
            }
        } else if (src instanceof java.lang.Number) {
            uid = ((java.lang.Number) src).intValue();
        } else if (src instanceof java.lang.String) {
            try {
                uid = java.lang.Integer.parseInt(((java.lang.String) src).trim());
            } catch (java.lang.Throwable th) {
            }
        }
        if (uid <= 0) {
            uid = req.optInt("source_uid", 0);
        }
        if (uid <= 0) {
            uid = req.optInt("inherit_uid", 0);
        }
        if (uid <= 0 && target != null) {
            uid = target.optInt("CD", 0);
        }
        if (uid <= 0) {
            org.json.JSONObject logs = req.optJSONObject("logs");
            if (logs != null) {
                org.json.JSONArray arr = logs.optJSONArray("54");
                if (arr == null) {
                    arr = logs.optJSONArray("27");
                }
                if (arr != null && arr.length() > 0) {
                    org.json.JSONObject e = arr.optJSONObject(arr.length() - 1);
                    org.json.JSONObject m = e == null ? null : e.optJSONObject("M");
                    if (m != null) {
                        uid = m.optInt("A5", 0);
                        if (uid <= 0) {
                            uid = m.optInt("A2", 0);
                        }
                    }
                }
            }
        }
        if (uid <= 0) {
            org.json.JSONArray mats = req.optJSONArray("material");
            if (mats != null) {
                for (int i = 0; i < mats.length(); i++) {
                    org.json.JSONObject m = mats.optJSONObject(i);
                    if (m != null && m.optInt("A5", 0) > 0) {
                        uid = m.optInt("A5", 0);
                        break;
                    }
                }
            }
        }
        return uid;
    }

    /** 锻升的可见加成：传承槽那件装备的属性 × 传承等级%（没槽就自基准，保持 fix191 的效果）。 */
    private static void applyInheritBonus(org.json.JSONObject item, int ce) throws java.lang.Exception {
        if (ce <= 0) {
            return;
        }
        org.json.JSONObject src = item.optJSONObject("CQ");
        if (src == null) {
            src = new org.json.JSONObject();
            java.lang.String[] base = {"A7", "A8", "A9", "AA", "AC"};
            for (int i = 0; i < base.length; i++) {
                if (item.has(base[i])) {
                    src.put(base[i], item.get(base[i]));
                }
            }
            item.put("CQ", src);
        }
        double rate = java.lang.Math.min(ce, 100) / 100.0d;
        java.lang.String[] attrKeys = {"A7", "A9", "AA", "AF"};
        for (int i = 0; i < attrKeys.length; i++) {
            int sv = src.optInt(attrKeys[i], 0);
            if (sv <= 0) {
                continue;
            }
            item.put(attrKeys[i], item.optInt(attrKeys[i], 0) + (int) java.lang.Math.round(sv * rate));
        }
    }

'''

NEW_HANDLE = r'''    private static void handleAction(org.json.JSONObject req, org.json.JSONObject r) {
        int ok = 1;
        java.lang.String opName = null;
        org.json.JSONObject extra = new org.json.JSONObject();
        org.json.JSONObject item = null;
        try {
            int type = req.optInt("type", 0);
            org.json.JSONObject target = req.optJSONObject("target");
            trace("HANDLEACTION type=" + type + " target=" + (target == null ? "null" : target.toString()));
            org.json.JSONObject store = readSaveObj();
            org.json.JSONObject bag = store.optJSONObject("bag");
            org.json.JSONArray items = bag == null ? null : bag.optJSONArray("item");
            item = findItem(items, target);
            org.json.JSONArray update = new org.json.JSONArray();
            org.json.JSONArray cost = new org.json.JSONArray();
            java.lang.String okMsg = "升品成功";
            if (item != null && target != null) {
                java.util.Iterator it = target.keys();
                while (it.hasNext()) {
                    java.lang.String k = (java.lang.String) it.next();
                    if ("A5".equals(k) || "A2".equals(k)) {
                        continue;
                    }
                    item.put(k, target.get(k));
                }
            }
            if (item != null) {
                int quality = item.optInt("CF", 0);
                if (quality <= 0) {
                    quality = item.optInt("CL", 0);
                }
                int star = item.optInt("CC", 0);
                if (type == 4) {
                    quality += 1;
                    item.put("CF", quality);
                    item.put("CL", quality);
                    okMsg = "升品成功";
                } else if (type == 5) {
                    star = java.lang.Math.min(5, star + 1);
                    item.put("CC", star);
                    okMsg = "升星成功";
                } else if (type == 6) {
                    int cu = java.lang.Math.max(item.optInt("CE", 0),
                            java.lang.Math.max(item.optInt("CU", 0), item.optInt("CW", 0)));
                    int limit = item.optInt("CV", 0);
                    if (limit <= 0 || limit > 100) {
                        limit = 100;
                    }
                    if (cu >= limit) {
                        ok = 0;
                        okMsg = "该装备已锻升到上限！";
                    } else {
                        int[] fc = fusionCost(cu);
                        boolean useAura = fc[2] == ITEM_XIANQI;
                        boolean have206 = bagHasStack(items, ITEM_RONG_LIANDAN, fc[1]);
                        boolean have2 = useAura ? auraCount(store) >= fc[3]
                                : bagHasStack(items, ITEM_YUANLING, fc[3]);
                        if (!have206 || !have2) {
                            ok = 0;
                            okMsg = "材料不足：熔炼丹×" + fc[1] + "、"
                                    + (useAura ? "仙气×" : "元灵精魄×") + fc[3];
                        } else {
                            takeStack(items, ITEM_RONG_LIANDAN, fc[1], cost);
                            if (useAura) {
                                addAura(store, -fc[3]);
                                cost.put(costEntry(ITEM_XIANQI, fc[3]));
                            } else {
                                takeStack(items, ITEM_YUANLING, fc[3], cost);
                            }
                            cu++;
                            item.put("CE", cu);
                            item.put("CU", cu);
                            item.put("CW", cu);
                            item.put("CV", 100);
                            applyNoblestStats(item, item.optInt("CF", item.optInt("CL", 0)),
                                    item.optInt("CC", 0));
                            applyInheritBonus(item, cu);
                            okMsg = "锻升成功";
                            opName = "noblest_inheritance_level";
                            extra.put("field", "CE");
                            extra.put("before", cu - 1);
                            extra.put("after", cu);
                        }
                    }
                } else if (type == 9) {
                    int cu = item.optInt("CE", 0);
                    if (cu >= 100) {
                        ok = 0;
                        okMsg = "该装备传承等级达到聚炼丹效果上限，无法使用";
                    } else if (!bagHasStack(items, ITEM_JULIANDAN, 1)) {
                        ok = 0;
                        okMsg = "聚炼丹不足";
                    } else {
                        takeStack(items, ITEM_JULIANDAN, 1, cost);
                        item.put("CV", 100);
                        item.put("CM", 0);
                        okMsg = "聚炼成功";
                        opName = "noblest_concentrate";
                    }
                } else if (type == 27 || type == 54) {
                    int srcUid = inheritSourceUid(req, target);
                    org.json.JSONObject srcItem = findByA5(items, srcUid);
                    if (srcUid <= 0) {
                        ok = 0;
                        okMsg = "请选择传承装备";
                    } else if (srcItem == null) {
                        ok = 0;
                        okMsg = "继承装备不存在！";
                    } else if (item.optInt("A5", 0) == srcUid) {
                        ok = 0;
                        okMsg = "不能把自己放进传承槽";
                    } else {
                        int self = item.optInt("A5", 0);
                        item.put("CD", srcUid);
                        item.remove("CQ");
                        org.json.JSONObject cq = new org.json.JSONObject(srcItem.toString());
                        cq.put("CG", self);
                        item.put("CQ", cq);
                        srcItem.put("CG", self);
                        org.json.JSONObject cgMap = new org.json.JSONObject();
                        cgMap.put(java.lang.String.valueOf(self), srcUid);
                        extra.put("update_cg", cgMap);
                        extra.put("target_item", item);
                        extra.put("add_goods", new org.json.JSONArray());
                        extra.put("remove_goods", new org.json.JSONArray());
                        extra.put("source_uid", srcUid);
                        okMsg = "传承成功";
                        opName = "noblest_inlay";
                        trace("HANDLEACTION inlay target=" + self + " <- source=" + srcUid);
                    }
                }
                if (type != 6) {
                    applyNoblestStats(item, quality, star);
                }
                update.put(item);
                if (type != 6 && type != 9 && type != 27 && type != 54) {
                    org.json.JSONArray mats = req.optJSONArray("material");
                    if (mats != null && items != null) {
                        for (int i = 0; i < mats.length(); i++) {
                            org.json.JSONObject m = mats.optJSONObject(i);
                            if (m == null) {
                                continue;
                            }
                            int need = m.optInt("A4", 0);
                            if (need <= 0) {
                                continue;
                            }
                            org.json.JSONObject have = findStack(items, m.optInt("A2", 0));
                            if (have != null) {
                                have.put("A4", java.lang.Math.max(0, have.optInt("A4", 0) - need));
                                cost.put(have);
                            }
                        }
                    }
                }
                writeSave(store.toString());
                trace("HANDLEACTION done type=" + type + " A5=" + item.optInt("A5", 0)
                        + " CE=" + item.optInt("CE", 0) + " CV=" + item.optInt("CV", 0)
                        + " CF=" + item.optInt("CF", 0) + " CL=" + item.optInt("CL", 0)
                        + " CC=" + item.optInt("CC", 0) + " CD=" + item.optInt("CD", 0)
                        + " CQ=" + (item.optJSONObject("CQ") != null) + " result=" + ok);
            } else {
                trace("HANDLEACTION target not found");
            }
            org.json.JSONObject info = new org.json.JSONObject();
            info.put("result", ok);
            info.put("update", update);
            info.put("msg", okMsg);
            info.put("titletip", okMsg);
            if (cost.length() > 0) {
                info.put("cost", cost);
            }
            r.put("info", info);
            if (opName != null) {
                r.put("operation", opName);
            }
            if (item != null) {
                r.put("uid", java.lang.String.valueOf(item.optInt("A5", 0)));
            }
            java.util.Iterator ek = extra.keys();
            while (ek.hasNext()) {
                java.lang.String k = (java.lang.String) ek.next();
                r.put(k, extra.get(k));
            }
        } catch (java.lang.Throwable th) {
            trace("handleAction failed " + th);
            try {
                org.json.JSONObject info = new org.json.JSONObject();
                info.put("result", 0);
                info.put("msg", "操作失败");
                r.put("info", info);
            } catch (java.lang.Throwable th2) {
            }
        }
    }

'''


def main():
    with io.open(P, encoding="utf-8") as f:
        src = f.read()

    # 1) equipJson 补至尊字段
    old = '''            jSONObject.put("CG", 0);
            jSONObject.put("CN", 0);'''
    new = '''            jSONObject.put("CG", 0);
            jSONObject.put("CL", 0);
            jSONObject.put("CM", 1);
            jSONObject.put("CN", 0);
            jSONObject.put("CU", 0);
            jSONObject.put("CV", 100);
            jSONObject.put("CW", 0);'''
    assert old in src, "equipJson anchor"
    src = src.replace(old, new, 1)

    # 2) applyNoblestStats 用 noblest_enhance_data_id（11.4 实证：用 enhance_data_id 查不到→不加属性）
    old2 = 'jSONObjectOptJSONObject2.optInt("enhance_data_id", 0)'
    new2 = ('(jSONObjectOptJSONObject2.optInt("noblest_enhance_data_id", 0) != 0'
            ' ? jSONObjectOptJSONObject2.optInt("noblest_enhance_data_id", 0)'
            ' : jSONObjectOptJSONObject2.optInt("enhance_data_id", 0))')
    assert old2 in src, "applyNoblestStats anchor"
    src = src.replace(old2, new2, 1)

    # 3) roledata2 应答顶层补 extremeCnf + slot_stunt_*
    old3 = '''        jSONObject2.put("pkservice", jSONObject3);
        jSONObject2.put("itemlimit", new org.json.JSONArray());
    }

    private static synchronized void syncProfileFromSave('''
    new3 = '''        jSONObject2.put("pkservice", jSONObject3);
        jSONObject2.put("extremeCnf", extremeCnf());
        putSlotStuntConfig(jSONObject2);
        jSONObject2.put("itemlimit", new org.json.JSONArray());
    }

    private static synchronized void syncProfileFromSave('''
    assert old3 in src, "fillSave anchor"
    src = src.replace(old3, new3, 1)

    # 4) 替换 handleAction
    a = src.index("    private static void handleAction(org.json.JSONObject")
    b = src.index("    private static void applyNoblestStats(", a)
    src = src[:a] + NEW_HANDLE + src[b:]

    # 5) 在 applyNoblestStats 前插入辅助方法
    c = src.index("    private static void applyNoblestStats(")
    src = src[:c] + HELPERS + src[c:]

    with io.open(P, "w", encoding="utf-8", newline="\n") as f:
        f.write(src)
    print("patched, lines:", src.count("\n"))


main()
