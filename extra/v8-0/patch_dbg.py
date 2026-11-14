# -*- coding: utf-8 -*-
"""加诊断 + 让 adopt 在 bag/item 为空时也能补。"""
import io

P = r"v800\java\org\yjmobile\zmxy\offline\LocalServer.java"

OLD = '''            item = findItem(items, target);
            if (item == null && target != null && items != null
                    && target.optInt("A5", 0) > 0) {
                // 服务端存档里没有这件装备（邮件附件以前只发给客户端、没入库）。
                // 客户端既然有，就用请求里的完整条目补进服务端背包，后续照常处理。
                item = new org.json.JSONObject(target.toString());
                items.put(item);'''

NEW = '''            item = findItem(items, target);
            trace("HANDLEACTION dbg bagNull=" + (bag == null) + " itemsNull=" + (items == null)
                    + " tgtA5=" + (target == null ? -1 : target.optInt("A5", 0)));
            if (item == null && target != null && target.optInt("A5", 0) > 0) {
                // 服务端存档里没有这件装备（邮件附件以前只发给客户端、没入库）。
                // 客户端既然有，就用请求里的完整条目补进服务端背包，后续照常处理。
                if (bag == null) {
                    bag = new org.json.JSONObject();
                    store.put("bag", bag);
                }
                if (items == null) {
                    items = new org.json.JSONArray();
                    bag.put("item", items);
                }
                item = new org.json.JSONObject(target.toString());
                items.put(item);'''

src = io.open(P, encoding="utf-8").read()
assert OLD in src, "anchor not found"
src = src.replace(OLD, NEW, 1)
io.open(P, "w", encoding="utf-8", newline="\n").write(src)
print("patched2 ok")
