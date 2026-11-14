"""The game's request/response codec:  -=4399=-  + base64(DES-ECB/PKCS5, key).

Pulled from the v8 LocalServer (Des.java) and confirmed against a live capture of
the 11.4.0 checkupdate POST (v11/mj.log).
"""
import base64
import sys

KEY = b'43((zmIp'
PREFIX = b'-=4399=-'

try:
    from cryptography.hazmat.decrepit.ciphers import algorithms as _alg
    from cryptography.hazmat.primitives.ciphers import Cipher, modes
    TripleDES = _alg.TripleDES
except Exception:                                        # older layout
    from cryptography.hazmat.primitives.ciphers import algorithms
    Cipher = __import__('cryptography.hazmat.primitives.ciphers', fromlist=['Cipher']).Cipher
    modes = __import__('cryptography.hazmat.primitives.ciphers.modes',
                       fromlist=['modes']).modes
    TripleDES = algorithms.TripleDES


def _cipher(enc):
    c = Cipher(TripleDES(KEY), modes.ECB())
    return c.encryptor() if enc else c.decryptor()


def decode(body):
    raw = body.strip()
    if raw.startswith(PREFIX):
        raw = raw[len(PREFIX):]
    data = base64.b64decode(raw + b'=' * (-len(raw) % 4))
    plain = _cipher(False).update(data) + _cipher(False).finalize()
    if plain and plain[-1] <= 16 and plain.endswith(bytes([plain[-1]]) * plain[-1]):
        plain = plain[:-plain[-1]]
    return plain


def encode(text, prefix=True):
    data = text.encode('utf-8') if isinstance(text, str) else text
    pad = 8 - (len(data) % 8)
    data += bytes([pad]) * pad
    out = _cipher(True).update(data) + _cipher(True).finalize()
    b = base64.b64encode(out)
    return (PREFIX + b) if prefix else b


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '-e':
        print(encode(sys.stdin.buffer.read()).decode())
    elif len(sys.argv) > 1:
        print(decode(sys.argv[1].encode()).decode('utf-8', 'replace'))
    else:
        print(decode(sys.stdin.buffer.read()).decode('utf-8', 'replace'))
