# -*- coding: utf-8 -*-
"""在 libgame_plain.so 里按 ARM PIC 惯用法定位字符串 / 数据引用。

惯用法（已用 InlayLayer vtable 验证）：
    ldr rX, [pc, #k]      ; 池字 W = target - (add指令地址 + 8)
    add rX, pc, rX
扫描 .text 里每个 4 字节池字，反解出候选 add 指令地址，再用编码校验是不是
`add Rd, pc, Rm`（0xE08F_0_00_0），并检查池字离 add 不超过若干 KB。
"""
import struct
import sys

BIN = r"native\libgame_plain.so"
d = open(BIN, "rb").read()

TEXT_LO, TEXT_HI = 0x5E4D30, 0x1AA3BB4


def is_add_pc(w):
    # ADD{cond} Rd, pc, Rm  ->  1110 0000 1000 1111 Rd 0000 0000 Rm
    return (w & 0xFFF0F000) == 0xE08F0000


def find_refs(target, pool_window=0x2000, limit=40):
    out = []
    for p in range(TEXT_LO, TEXT_HI, 4):
        w = struct.unpack_from("<I", d, p)[0]
        if w == 0 or w > 0x4000000:
            continue
        a = target - w - 8
        if not (TEXT_LO <= a < TEXT_HI):
            continue
        if not (0 < p - a <= pool_window):
            continue
        iw = struct.unpack_from("<I", d, a)[0]
        if not is_add_pc(iw):
            continue
        out.append((p, a, w))
        if len(out) >= limit:
            break
    return out


if __name__ == "__main__":
    targets = {
        "pointer-table 0x1d0333c": 0x1D0333C,
        "属性传承比例": 0x1C0D0A8,
        "传承等级": 0x1C0CA30,
        "聚炼剩余时间": 0x1C0CFB0,
        "传承装备属性说明": 0x1C0D068,
        "99%": 0x1C0D0C8,
        "%d%%": 0x1BEF268,
        "%.1f%%": 0x1BF68B8,
    }
    for name, t in targets.items():
        refs = find_refs(t)
        print("=== %s  0x%x  refs=%d" % (name, t, len(refs)))
        for p, a, w in refs:
            print("    pool@0x%x  W=0x%x  add@0x%x" % (p, w, a))
