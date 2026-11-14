"""把 magic.sdb 里四把仙剑的 slot_count_per 改成 [0,0,0,0,1]（必出 5 孔）。

保留原 JSON 文本格式，只替换那四行的数组，重加密后写回。
"""
import base64
import io
import json
import sys

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

SRC = r'<tmp>/magic_now.sdb'
DST = r'<tmp>/magic_patched.sdb'
KEY = b'1#(z1Rr>' * 3
SWORDS = ['500016', '500015', '500014', '500013']


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
    for sid in SWORDS:
        key = '"' + sid + '":'
        at = text.find(key)
        if at < 0:
            print('missing', sid)
            continue
        sp = text.find('"slot_count_per":', at)
        if sp < 0:
            print('no slot_count_per for', sid)
            continue
        op = text.find('[', sp)
        cl = text.find(']', op)
        old = text[op:cl + 1]
        text = text[:op] + '[0,0,0,0,1]' + text[cl + 1:]
        print(sid, 'replaced', old)
        n += 1
    print('patched rows', n)
    json.loads(text)   # 校验还是合法 JSON
    encrypt_to_file(text, DST)
    print('wrote', DST)


if __name__ == '__main__':
    main()
