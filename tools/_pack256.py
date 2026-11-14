# -*- coding: utf-8 -*-
"""fix256：只把新编出来的 classes2.dex 换进 fix255（其余字节原样），产出未签名包。"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from _zipswap import raw_zip_swap  # noqa: E402

SRC = r"<tmp>/fix255.apk"
DST = r"<tmp>/fix256.unsigned"
DEX = r"<tmp>/dex\classes.dex"

repl = {"classes2.dex": open(DEX, "rb").read()}
print("swap classes2.dex %d bytes -> %s" % (len(repl["classes2.dex"]), DST))
raw_zip_swap(SRC, DST, repl)
print("ok", DST, os.path.getsize(DST))
