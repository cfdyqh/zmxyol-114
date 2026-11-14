"""Decrypt an 11.4.0 .sdb table (base64 + DES-ECB, key 43((zmIp) and peek inside."""
import base64
import json
import sys

from cryptography.hazmat.decrepit.ciphers import algorithms as alg
from cryptography.hazmat.primitives.ciphers import Cipher, modes

KEY = b'43((zmIp'


def decrypt(path):
    raw = open(path, 'rb').read()
    data = base64.b64decode(raw)
    c = Cipher(alg.TripleDES(KEY), modes.ECB()).decryptor()
    p = c.update(data) + c.finalize()
    k = p[-1]
    if 1 <= k <= 8 and p[-k:] == bytes([k]) * k:
        p = p[:-k]
    return p


if __name__ == '__main__':
    path = sys.argv[1] if len(sys.argv) > 1 else r'<path>'
    out = sys.argv[2] if len(sys.argv) > 2 else path + '.json'
    p = decrypt(path)
    open(out, 'wb').write(p)
    print('%s -> %d bytes -> %s' % (path, len(p), out))
    try:
        j = json.loads(p.decode('utf-8'))
        if isinstance(j, dict):
            print('top keys:', list(j.keys())[:10])
            for k in list(j.keys())[:2]:
                v = j[k]
                print(' %r -> %s' % (k, json.dumps(v, ensure_ascii=False)[:400]))
        elif isinstance(j, list):
            print('list of %d, first: %s' % (len(j), json.dumps(j[0], ensure_ascii=False)[:400]))
    except Exception as e:
        print('not json: %r' % (e,))
        sys.stdout.write(p[:600].decode('utf-8', 'replace') + '\n')
