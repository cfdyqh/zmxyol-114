# -*- coding: utf-8 -*-
"""把合并后的 equip.sdb 换进 fix251，产出 fix252.unsigned（不做签名，交给 ps1）。"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from _zipswap import raw_zip_swap   # noqa: E402

SRC = r'<tmp>/fix251.apk'
DST = r'<tmp>/fix252.unsigned'
SDB = os.path.join(HERE, '_out', 'equip.sdb')

repl = {'assets/database/equip.sdb': open(SDB, 'rb').read()}
print('swap', SDB, len(repl['assets/database/equip.sdb']), 'bytes')
raw_zip_swap(SRC, DST, repl)
print('ok ->', DST, os.path.getsize(DST))
