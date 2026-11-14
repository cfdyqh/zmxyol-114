# -*- coding: utf-8 -*-
"""修 jadx 反编译产物里的 catch 变量别名（`th = th2;` / `e = e2;`）导致的不编译问题。"""
import io
import os
import re
import sys

ROOT = r"<tmp>/src"
ALIAS = re.compile(r"^(\s*)([A-Za-z_]\w*)\s*=\s*([A-Za-z_]\w*);\s*$")
CATCH = re.compile(r"catch\s*\(\s*([\w.]+)\s+([A-Za-z_]\w*)\s*\)")
NAMEISH = re.compile(r"^(th|e)\d*$")


def fix(path):
    with io.open(path, encoding="utf-8") as f:
        lines = f.read().split("\n")
    depth = 0
    stack = []          # [(depth_at_block, type, name)]
    changed = 0
    out = []
    for line in lines:
        # 先看这一行是不是 catch 头
        m = CATCH.search(line)
        if m:
            stack.append((depth, m.group(1), m.group(2)))
        a = ALIAS.match(line)
        if a:
            indent, lhs, rhs = a.group(1), a.group(2), a.group(3)
            if NAMEISH.match(lhs) and NAMEISH.match(rhs):
                if lhs == rhs:
                    changed += 1
                    continue                    # 自赋值，直接删
                typ = "java.lang.Throwable"
                for d, t, n in reversed(stack):
                    if d <= depth and t:
                        typ = t
                        break
                out.append("%s%s %s = %s;" % (indent, typ, lhs, rhs))
                changed += 1
                continue
        out.append(line)
        depth += line.count("{") - line.count("}")
        while stack and depth <= stack[-1][0]:
            stack.pop()
    if changed:
        with io.open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(out))
    return changed


total = 0
for base, _, files in os.walk(ROOT):
    for fn in files:
        if fn.endswith(".java"):
            n = fix(os.path.join(base, fn))
            if n:
                print("%-30s fixed %d" % (fn, n))
                total += n
print("total", total)
