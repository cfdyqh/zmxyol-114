# -*- coding: utf-8 -*-
"""解密 11.4 APK 里的 sdb 表并做定点检查（至尊 / 传承 / 材料）。"""
import base64
import json
import sys

from cryptography.hazmat.decrepit.ciphers import algorithms as alg
from cryptography.hazmat.primitives.ciphers import Cipher, modes

KEY = b"1#(z1Rr>" * 3
APK = r"<tmp>/fix255.apk"


def dec_sdb(raw):
    s = raw
    if s.startswith(b"-=4399=-"):
        s = s[len(b"-=4399=-"):]
    s = b"".join(s.split())
    s += b"=" * ((4 - len(s) % 4) % 4)
    data = base64.b64decode(s)
    c = Cipher(alg.TripleDES(KEY), modes.ECB()).decryptor()
    p = c.update(data) + c.finalize()
    k = p[-1] if p else 0
    if 1 <= k <= 8 and p[-k:] == bytes([k]) * k:
        p = p[:-k]
    return p


def table(name):
    import zipfile
    z = zipfile.ZipFile(APK)
    raw = z.read("assets/database/%s.sdb" % name)
    txt = dec_sdb(raw).decode("utf-8", "replace")
    try:
        return json.loads(txt)
    except Exception:
        # 有的表是多行 JSON 数组
        return json.loads("[" + txt.strip().strip("[]") + "]")


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "noblest_equip"
    t = table(which)
    rows = t if isinstance(t, list) else list(t.values())
    print("table %s rows=%d type=%s" % (which, len(rows), type(t).__name__))
    if isinstance(t, dict):
        keys = list(t.keys())
        print("  id sample:", keys[:8])
    first = rows[0] if rows else {}
    if isinstance(first, dict):
        print("  fields:", sorted(first.keys()))
    for want in sys.argv[2:]:
        for r in rows:
            if not isinstance(r, dict):
                continue
            if str(r.get("id")) == want or str(r.get("dataid")) == want:
                print("  --- id=%s ---" % want)
                print("   ", json.dumps(r, ensure_ascii=False))
                break
