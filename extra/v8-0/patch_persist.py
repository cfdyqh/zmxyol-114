# -*- coding: utf-8 -*-
"""8.0 修复三：把 buildSavedataBlock 的产物回写存档（bag.item 才不会再丢）+ 补至尊材料。"""
import io

P = r"v800\java\org\yjmobile\zmxy\offline\LocalServer.java"

WRAP = r'''    /**
     * 下发存档后**回写**服务端存档，并补齐至尊材料。
     *
     * <p>8.0 原来的 buildSavedataBlock 只是「读存档 → 拼一份发给客户端」，从不写回。
     * 而客户端周期上传的 savedata 里 bag 没有 item 成员（实测），于是服务端存档的
     * bag 一直是空的：邮件附件、下发时补的材料、至尊装备全都不在里面 →
     * handleaction 的 findItem 永远找不到那件装备（实测日志
     * {@code HANDLEACTION dbg bagNull=false itemsNull=true}），升品/升星/锻升全部空转。
     */
    private static org.json.JSONObject buildSavedataBlock() {
        org.json.JSONObject sd = buildSavedataBlock0();
        if (sd != null) {
            try {
                ensureNoblestMaterials(sd);
                writeSave(sd.toString());
            } catch (java.lang.Throwable th) {
                trace("buildSavedataBlock persist failed " + th);
            }
        }
        return sd;
    }

    /** 至尊/传承用的材料：熔炼丹 / 聚炼丹 / 元灵精魄 / 六色玄晶 / 三种碎片，缺了就补 9999。 */
    private static void ensureNoblestMaterials(org.json.JSONObject sd) {
        try {
            org.json.JSONObject bag = sd.optJSONObject("bag");
            if (bag == null) {
                bag = new org.json.JSONObject();
                sd.put("bag", bag);
            }
            org.json.JSONArray items = bag.optJSONArray("item");
            if (items == null) {
                items = new org.json.JSONArray();
                bag.put("item", items);
            }
            int[] ids = {503206, 503212, 503199, 503200, 503201, 503202, 503203, 503204,
                    503205, 503208, 503209, 503210};
            int added = 0;
            for (int i = 0; i < ids.length; i++) {
                int id = ids[i];
                boolean have = false;
                for (int j = 0; j < items.length(); j++) {
                    org.json.JSONObject it = items.optJSONObject(j);
                    if (it != null && it.optInt("A2", 0) == id) {
                        if (it.optInt("A4", 0) < 9999) {
                            it.put("A4", 9999);
                        }
                        have = true;
                        break;
                    }
                }
                if (!have) {
                    items.put(itemJson(id, 9999));
                    added++;
                }
            }
            trace("NOBLEST materials +" + added + " bag items=" + items.length());
        } catch (java.lang.Throwable th) {
            trace("ensureNoblestMaterials failed " + th);
        }
    }

'''

src = io.open(P, encoding="utf-8").read()

old_head = "    private static org.json.JSONObject buildSavedataBlock() {"
assert old_head in src, "buildSavedataBlock not found"
src = src.replace(old_head, "    private static org.json.JSONObject buildSavedataBlock0() {", 1)

# 在原方法前插入包装 + 材料补齐
i = src.index("    private static org.json.JSONObject buildSavedataBlock0() {")
src = src[:i] + WRAP + src[i:]

io.open(P, "w", encoding="utf-8", newline="\n").write(src)
print("patched3 ok")
