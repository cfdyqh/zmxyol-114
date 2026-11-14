"""把 aura_ball.sdb 里 8 颗「史诗」启灵珠的 limitbreak_id 清 0（关闭突破入口）。

配合 libtprt 垫片里的 patch_aura_cap()：史诗(grade 4)等级上限 120，
已经不需要破灵符突破，所以把 limitbreak_id 置 0，客户端不再显示/接受突破。

保留原 JSON 文本格式，只改这些行的 limitbreak_id，重加密后写回。
"""
import base64
import io
import re
import sys

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

SRC = r'<tmp>/aura_now.sdb'
DST = r'<tmp>/aura_patched.sdb'
KEY = b'1#(z1Rr>' * 3

# 502160-502165 血/魔/攻/防/暴/闪，502168 魔抗，502192 爆伤
EPIC = ['502160', '502161', '502162', '502163', '502164', '502165', '502168', '502192']


def decrypt_to_text(path):
    raw = io.open(path, 'r', encoding='latin1').read().strip()
    if raw.startswith('-=4399=-'):
        raw = raw[8:]
    data = base64.b64decode(raw)
    dec = Cipher(algorithms.TripleDES(KEY), modes.ECB()).decryptor()
    out = dec.update(data) + dec.finalize()
    pad = out[-1]
    if 0 < pad <= 8:
        out = out[:-pad]
    return out.decode('utf-8')


def encrypt_to_file(text, path):
    body = text.encode('utf-8')
    pad = 8 - (len(body) % 8)
    body = body + bytes([pad]) * pad
    enc = Cipher(algorithms.TripleDES(KEY), modes.ECB()).encryptor()
    out = enc.update(body) + enc.finalize()
    b64 = base64.b64encode(out).decode('ascii')
    lines = [b64[i:i + 64] for i in range(0, len(b64), 64)]
    io.open(path, 'w', encoding='latin1').write('\n'.join(lines) + '\n')


def main():
    text = decrypt_to_text(SRC)
    n = 0
    for sid in EPIC:
        start = text.find('"' + sid + '":')
        if start < 0:
            print('missing', sid)
            continue
        end = text.find('}, "', start)
        if end < 0:
            end = len(text)
        row = text[start:end]
        m = re.search(r'"limitbreak_id"\s*:\s*\d+', row)
        if not m:
            print('no limitbreak_id for', sid)
            continue
        old = m.group(0)
        row2 = row[:m.start()] + '"limitbreak_id": 0' + row[m.end():]
        text = text[:start] + row2 + text[end:]
        print(sid, 'replaced', old)
        n += 1
    print('patched rows', n)
    assert n == len(EPIC), 'not all epic rows patched'
    import json
    json.loads(text)          # 校验还是合法 JSON
    encrypt_to_file(text, DST)
    print('wrote', DST)


if __name__ == '__main__':
    main()
