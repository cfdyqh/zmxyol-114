# -*- coding: utf-8 -*-
"""严格版：ldr rD,[pc,#imm] 紧接 add rX,pc,rD —— 只有这一对才算真引用。

ARM 编码：
  LDR（立即数偏移） 0xE59F0000 | Rd<<12 | imm12
  ADD Rd,pc,Rm      0xE08F0000 | Rd<<12 | Rm
池字 W 在 p，则 p == (add_addr - 4) + 8 + imm12，且 target == add_addr + 8 + W。
"""
import struct
import sys

BIN = r"native\libgame_plain.so"
d = open(BIN, "rb").read()
TEXT_LO, TEXT_HI = 0x5E4D30, 0x1AA3BB4


def u32(va):
    return struct.unpack_from("<I", d, va)[0]


def find_refs(target, limit=40):
    out = []
    for p in range(TEXT_LO, TEXT_HI, 4):
        w = u32(p)
        if w == 0 or w > 0x8000000:
            continue
        a = target - w - 8
        if not (TEXT_LO <= a < TEXT_HI):
            continue
        iw = u32(a)
        if (iw & 0xFFFF0000) != 0xE08F0000:
            continue
        rm = iw & 0xF
        for prev in (a - 4, a - 8):
            pi = u32(prev)
            if (pi & 0xFFFF0000) != 0xE59F0000:
                continue
            rdn = (pi >> 12) & 0xF
            imm = pi & 0xFFF
            if rdn != rm:
                continue
            lit = prev + 8 + imm
            if lit == p:
                out.append((p, a, w, prev))
                break
        if len(out) >= limit:
            break
    return out


TARGETS = {
    "vtable InlayLayer-8": 0x1D5C898,
    "属性传承比例": 0x1C0D0A8,
    "传承等级": 0x1C0CA30,
    "传承比例": 0x1C0CA40,
    "聚炼剩余时间%02ld": 0x1C0CFB0,
    "传承装备说明": 0x1C0D068,
    "handleaction": 0x1BE3040,
    "savedata": 0x1BE5FC8,
}

if __name__ == "__main__":
    for name, t in TARGETS.items():
        refs = find_refs(t)
        print("=== %-22s 0x%-9x refs=%d" % (name, t, len(refs)))
        for p, a, w, prev in refs:
            print("      pool@0x%x  add@0x%x  (ghidra func addr ~0x%x)" % (p, a, a + 0x10000))
