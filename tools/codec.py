"""Final wire codec for 11.4.0:  -=4399=- + base64( DES-ECB/PKCS5 )

Key recovered from libgame's .bss (offset 0x1104): b'1#(z1Rr>'
Confirmed by decrypting the captured checkupdate body -> '{"ip":"1...'
"""
import base64
import sys

from cryptography.hazmat.decrepit.ciphers import algorithms as alg
from cryptography.hazmat.primitives.ciphers import Cipher, modes

KEY = b'1#(z1Rr>'
PREFIX = b'-=4399=-'


def _c(enc):
    c = Cipher(alg.TripleDES(KEY), modes.ECB())
    return c.encryptor() if enc else c.decryptor()


def decode(body):
    raw = body.strip()
    if raw.startswith(PREFIX):
        raw = raw[len(PREFIX):]
    data = base64.b64decode(raw + b'=' * (-len(raw) % 4))
    out = _c(False).update(data) + _c(False).finalize()
    if out and 0 < out[-1] <= 8 and out.endswith(bytes([out[-1]]) * out[-1]):
        out = out[:-out[-1]]
    return out


def encode(text, prefix=True):
    data = text.encode('utf-8') if isinstance(text, str) else text
    pad = 8 - (len(data) % 8)
    data += bytes([pad]) * pad
    enc = _c(True).update(data) + _c(True).finalize()
    b = base64.b64encode(enc)
    return (PREFIX + b) if prefix else b


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '-e':
        sys.stdout.write(encode(sys.stdin.read()).decode() + '\n')
    elif len(sys.argv) > 1:
        sys.stdout.write(repr(decode(sys.argv[1].encode())) + '\n')
    else:
        sys.stdout.write(repr(decode(sys.stdin.buffer.read())) + '\n')
