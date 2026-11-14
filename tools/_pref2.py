# -*- coding: utf-8 -*-
"""按 ARM PIC 惯用法（ldr rX,[pc,#k] + add rX,pc,rX）定位字符串/数据引用（修正掩码）。

ADD Rd,pc,Rm 编码 = 0xE08F0000 | (Rd<<12) | Rm -> 高 16 位恒为 0xE08F。
池字 W 与 add 指令地址 A 的关系：target = A + 8 + W。
"""
import struct
import sys

BIN = r"native\libgame_plain.so"
d = open(BIN, "rb").read()
TEXT_LO, TEXT_HI = 0x5E4D30, 0x1AA3BB4


def find_refs(target, win=0x4000, limit=60):
    out = []
    for p in range(TEXT_LO, TEXT_HI, 4):
        w = struct.unpack_from("<I", d, p)[0]
        if w == 0 or w > 0x8000000:
            continue
        a = target - w - 8
        if not (TEXT_LO <= a < TEXT_HI):
            continue
        if not (0 < p - a <= win):
            continue
        iw = struct.unpack_from("<I", d, a)[0]
        if (iw & 0xFFFF0000) != 0xE08F0000:
            continue
        out.append((p, a, w))
        if len(out) >= limit:
            break
    return out


TARGETS = {
    "vtable InlayLayer-8": 0x1D5C898,
    "pointer-table 0x1d0333c": 0x1D0333C,
    "属性传承比例": 0x1C0D0A8,
    "传承等级": 0x1C0CA30,
    "传承比例": 0x1C0CA40,
    "聚炼剩余时间%02ld": 0x1C0CFB0,
    "传承装备说明": 0x1C0D068,
    "99%": 0x1C0D0C8,
    "%d%%": 0x1BEF268,
    "%.1f%%": 0x1BF68B8,
    "handleaction": 0x1BE3040,
}

for name, t in TARGETS.items():
    refs = find_refs(t)
    print("=== %-24s 0x%-9x refs=%d" % (name, t, len(refs)))
    for p, a, w in refs[:8]:
        print("      pool@0x%x W=0x%x  add@0x%x  Rm=r%d" % (
            p, w, a, struct.unpack_from("<I", d, a)[0] & 0xF))
