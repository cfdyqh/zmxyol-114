# -*- coding: utf-8 -*-
"""直接跟 8.0 mod 的内置本地服对话，验证 handleaction（升品/锻升/传承挂接）的应答。

用法：
  python probe80.py login
  python probe80.py ha <type> <A5> [sourceA5]
"""
import base64
import json
import socket
import sys
import time

from cryptography.hazmat.decrepit.ciphers import algorithms as alg
from cryptography.hazmat.primitives.ciphers import Cipher, modes

KEY = b"43((zmIp" * 3          # 单 DES == 3DES(k,k,k)
HOST, PORT = "127.0.0.1", 8077


def des(data, enc):
    if enc:
        pad = 8 - (len(data) % 8)
        data = data + bytes([pad]) * pad
    c = Cipher(alg.TripleDES(KEY), modes.ECB())
    op = c.encryptor() if enc else c.decryptor()
    out = op.update(data) + op.finalize()
    if not enc:
        # PKCS5 去填充
        if out and 1 <= out[-1] <= 8 and out[-out[-1]:] == bytes([out[-1]]) * out[-1]:
            out = out[:-out[-1]]
    return out


def post(obj):
    body = base64.b64encode(des(json.dumps(obj, ensure_ascii=False).encode("utf-8"), True))
    req = (b"POST /android/index.php HTTP/1.1\r\nHost: 127.0.0.1\r\n"
           b"Content-Type: application/octet-stream\r\n"
           b"Content-Length: " + str(len(body)).encode() + b"\r\nConnection: close\r\n\r\n" + body)
    s = socket.create_connection((HOST, PORT), timeout=10)
    s.sendall(req)
    buf = b""
    while True:
        try:
            chunk = s.recv(65536)
        except socket.timeout:
            break
        if not chunk:
            break
        buf += chunk
    s.close()
    head, _, payload = buf.partition(b"\r\n\r\n")
    try:
        raw = base64.b64decode(payload.strip() + b"==")
        plain = des(raw, False).decode("utf-8", "replace")
    except Exception:
        plain = repr(payload[:200])
    return plain


BASE = {
    "action": "login", "rid": 1001, "service": 1, "channel": 100,
    "cuid": "4399zmxyx", "cname": "4399zmxyx", "uuid": "64D8CD29FC6B5BEF7F21E4AEA5F7998A",
    "version": "s8.0.0", "dataversion": "8.0.0", "resourceverion": "8.0.0",
    "session": "local-session", "rolelv": 1, "gametime": 0,
}


def item(**kw):
    d = {"A2": 500223, "A4": 1, "A5": 500223, "A6": 0, "A7": 650, "A8": 1350, "A9": 5,
         "AA": 50, "AQ": 0, "AT": 0, "AV": 0, "AW": 0.0, "B7": 0,
         "BP": 0, "BQ": 0, "BR": 0, "BS": 0, "CA": 0, "CC": 0, "CD": 0, "CE": 0,
         "CF": 0, "CG": 0, "CH": 5, "CI": 5, "CJ": 5, "CK": 5, "CL": 0, "CM": 1,
         "CP": -9999999,
         "BG": [{"BH": -1, "BI": 0, "BJ": 0, "CB": 0} for _ in range(4)]}
    d.update(kw)
    return d


def main():
    what = sys.argv[1] if len(sys.argv) > 1 else "login"
    if what == "login":
        print(post(dict(BASE, action="login")))
        return
    t = int(sys.argv[2])
    a5 = int(sys.argv[3])
    src = int(sys.argv[4]) if len(sys.argv) > 4 else 0
    tgt = item(A5=a5, A2=a5)
    req = dict(BASE, action="handleaction", type=t, target=tgt,
               material=[{"A2": 503206, "A4": 0}, {"A2": 503035, "A4": 0}],
               logs={str(t): [{"M": [{"A2": 503206, "A4": 0}, {"A2": 503035, "A4": 0}],
                               "T": {"A2": a5, "A5": a5}}]})
    if src:
        req["source"] = {"A5": src}
        req["material"] = [{"A2": src, "A4": 1, "A5": src}]
    print(post(req))


if __name__ == "__main__":
    main()
