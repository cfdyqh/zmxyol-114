"""Decrypt / re-encrypt libgame.so's protected code segment.

Facts established by diffing the APK's libgame.so against a runtime dump
(v11/native/libgame_dec.so, 33,102,420 B, same layout):

  .text (file offset 0x5e4d30 .. 0x1aa3bb3) is XOR-encrypted with a single byte
  key 0xB5.  Everything else in the file is plaintext.

  At runtime libtprt.so's constructor decrypts those pages in place: the .text
  segment is mapped  rw-p  (not r-x!) at base+0x5e4000, and reading it at
  t+500 ms in the de-Legu build shows the plaintext (`f0 40 2d e9 ...`).
  That happens with or without the Legu Java shell, because libtprt is a
  DT_NEEDED of libgame.

So patching libgame is a three-step dance:

  python patch_libgame_xor.py --src libgame.so --out patched.so --script my_patch.py
      my_patch.py exposes  patch(data: bytearray) -> None   (data is PLAINTEXT)

The script decrypts, calls your patch, re-encrypts, and leaves every other
byte untouched (section headers included), so the result can go straight into
the APK with `repack.py --asset lib/armeabi/libgame.so=<patched.so>`.
"""
import argparse
import struct
import sys

KEY = 0xB5


def load(data):
    e_shoff = struct.unpack_from('<I', data, 0x20)[0]
    es = struct.unpack_from('<H', data, 0x2e)[0]
    n = struct.unpack_from('<H', data, 0x30)[0]
    si = struct.unpack_from('<H', data, 0x32)[0]
    secs = [struct.unpack_from('<IIIIIIIIII', data, e_shoff + k * es) for k in range(n)]
    shstr = secs[si]
    shd = bytes(data[shstr[4]:shstr[4] + shstr[5]])
    out = {}
    for f in secs:
        e = shd.find(b'\0', f[0])
        out[shd[f[0]:e].decode('latin1')] = f
    return out


def xor_range(buf, off, size, key=KEY):
    for i in range(off, off + size):
        buf[i] ^= key


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--script', help='python file defining patch(bytearray)')
    ap.add_argument('--decrypt-only', action='store_true')
    a = ap.parse_args()

    data = bytearray(open(a.src, 'rb').read())
    S = load(data)
    off, size = S['.text'][4], S['.text'][5]
    print('libgame .text: off=0x%x size=0x%x' % (off, size))

    xor_range(data, off, size)                      # -> plaintext
    # sanity: first instruction of .text should be a push (stmdb sp!,...)
    print('plaintext head:', data[off:off + 4].hex())

    if a.script:
        ns = {}
        exec(open(a.script, 'r', encoding='utf-8').read(), ns)
        ns['patch'](data)
        print('patch() applied')

    if a.decrypt_only:
        open(a.out, 'wb').write(bytes(data))
        print('wrote decrypted', a.out)
        return

    xor_range(data, off, size)                      # -> ciphertext again
    print('re-encrypted head:', data[off:off + 4].hex())
    open(a.out, 'wb').write(bytes(data))
    print('wrote', a.out, len(data), 'bytes')


if __name__ == '__main__':
    main()
