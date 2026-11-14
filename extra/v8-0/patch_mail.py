# -*- coding: utf-8 -*-
"""8.0 修复二：邮件附件真正入库 + handleaction 找不到装备时用请求里的 target 补进背包。"""
import io

P = r"v800\java\org\yjmobile\zmxy\offline\LocalServer.java"

GRANT = r'''    /**
     * 把邮件附件写进服务端背包。
     *
     * <p>8.0 原来的 {@link #claimMail()} 只把附件回给客户端（客户端自己加进背包），
     * 服务端存档里没有 → 之后点升品/升星/锻升时 {@code findItem} 找不到那件装备，
     * 整个分支被跳过，应答是 {@code result:1 + update:[]}，面板看起来「点了没反应」。
     * （实测日志：handleaction type=6 target A2=500223 → update 空数组。）
     */
    private static synchronized void grantAttachmentsToSave(org.json.JSONArray atts) {
        try {
            if (atts == null) {
                return;
            }
            org.json.JSONObject store = readSaveObj();
            org.json.JSONObject bag = store.optJSONObject("bag");
            if (bag == null) {
                bag = new org.json.JSONObject();
                store.put("bag", bag);
            }
            org.json.JSONArray items = bag.optJSONArray("item");
            if (items == null) {
                items = new org.json.JSONArray();
                bag.put("item", items);
            }
            int added = 0;
            for (int i = 0; i < atts.length(); i++) {
                org.json.JSONObject a = atts.optJSONObject(i);
                if (a == null) {
                    continue;
                }
                int a2 = a.optInt("A2", 0);
                int a4 = a.optInt("A4", 1);
                boolean stackable = a4 > 1 || a2 >= 503000;
                if (stackable) {
                    boolean have = false;
                    for (int j = 0; j < items.length(); j++) {
                        org.json.JSONObject it = items.optJSONObject(j);
                        if (it != null && it.optInt("A2", 0) == a2
                                && it.optInt("A4", 0) > 0) {
                            it.put("A4", Math.max(it.optInt("A4", 0), a4));
                            have = true;
                            break;
                        }
                    }
                    if (!have) {
                        items.put(new org.json.JSONObject(a.toString()));
                        added++;
                    }
                } else {
                    int a5 = a.optInt("A5", 0);
                    boolean have = false;
                    for (int j = 0; j < items.length(); j++) {
                        org.json.JSONObject it = items.optJSONObject(j);
                        if (it == null) {
                            continue;
                        }
                        if ((a5 > 0 && it.optInt("A5", 0) == a5)
                                || (a5 <= 0 && it.optInt("A2", 0) == a2)) {
                            have = true;
                            break;
                        }
                    }
                    if (!have) {
                        items.put(new org.json.JSONObject(a.toString()));
                        added++;
                    }
                }
            }
            writeSave(store.toString());
            trace("MAIL persist: +" + added + " -> bag items=" + items.length());
        } catch (java.lang.Throwable th) {
            trace("grantAttachmentsToSave failed " + th);
        }
    }

'''

with io.open(P, encoding="utf-8") as f:
    src = f.read()

# 1) claimMail 里补持久化
old = '''            grantPetToSave(PET_YAOSHENG);
            trace("MAIL claim granted " + jSONArray.length() + " stacks + pet " + PET_YAOSHENG);'''
new = '''            grantPetToSave(PET_YAOSHENG);
            grantAttachmentsToSave(jSONArrayMailAttachments);
            trace("MAIL claim granted " + jSONArray.length() + " stacks + pet " + PET_YAOSHENG);'''
assert old in src, "claimMail anchor"
src = src.replace(old, new, 1)

# 2) 插入 grantAttachmentsToSave（放在 claimMail 之前）
anchor = "    private static synchronized org.json.JSONArray claimMail() {"
i = src.index(anchor)
src = src[:i] + GRANT + src[i:]

# 3) handleAction 里 target 找不到时把它补进背包
old2 = '''            item = findItem(items, target);
            org.json.JSONArray update = new org.json.JSONArray();'''
new2 = '''            item = findItem(items, target);
            if (item == null && target != null && items != null
                    && target.optInt("A5", 0) > 0) {
                // 服务端存档里没有这件装备（邮件附件以前只发给客户端、没入库）。
                // 客户端既然有，就用请求里的完整条目补进服务端背包，后续照常处理。
                item = new org.json.JSONObject(target.toString());
                items.put(item);
                trace("HANDLEACTION adopt missing target A5=" + item.optInt("A5", 0)
                        + " A2=" + item.optInt("A2", 0));
            }
            org.json.JSONArray update = new org.json.JSONArray();'''
assert old2 in src, "handleAction anchor"
src = src.replace(old2, new2, 1)

with io.open(P, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("patched")
