"""Patch libgame.so PLT stubs for process-killing imports to harmless returns.

In the de-Legu build the shell's integrity check never runs, so libgame.so may be
modified.  This neutralises the natives that terminate the process
(kill/exit/_exit/abort/raise/tgkill/pthread_kill) so we can see what happens next.
"""
import struct, zipfile, sys

SRC = r'base(2).apk'
DST = r'native\libgame-patched.so'
KILLERS = {'kill', 'exit', '_exit', 'abort', 'raise', 'tgkill', 'pthread_kill'}
RET = struct.pack('<II', 0xE3A00000, 0xE12FFF1E)      # mov r0,#0 ; bx lr
NOP = struct.pack('<I', 0xE1A00000)

z = zipfile.ZipFile(SRC)
g = bytearray(z.read('lib/armeabi/libgame.so'))

e_shoff = struct.unpack_from('<I', g, 0x20)[0]
es = struct.unpack_from('<H', g, 0x2e)[0]
n = struct.unpack_from('<H', g, 0x30)[0]
si = struct.unpack_from('<H', g, 0x32)[0]
secs = [struct.unpack_from('<IIIIIIIIII', g, e_shoff + k * es) for k in range(n)]
sh = secs[si]
shd = bytes(g[sh[4]:sh[4] + sh[5]])
S = {}
for f in secs:
    e = shd.find(b'\0', f[0])
    S[shd[f[0]:e].decode('latin1')] = f

dynsym, dynstr = S['.dynsym'], S['.dynstr']
strd = bytes(g[dynstr[4]:dynstr[4] + dynstr[5]])
syms = []
for i in range(dynsym[5] // 16):
    o = dynsym[4] + i * 16
    sn, sv, ss, inf, oth, sx = struct.unpack_from('<IIIBBH', g, o)
    e = strd.find(b'\0', sn)
    syms.append(strd[sn:e].decode('latin1') if sn else '')

rel, plt = S['.rel.plt'], S['.plt']
patched = []
for i in range(rel[5] // 8):
    o = rel[4] + i * 8
    r_off, r_info = struct.unpack_from('<II', g, o)
    idx = r_info >> 8
    nm = syms[idx] if idx < len(syms) else '?'
    if nm not in KILLERS:
        continue
    addr = plt[3] + 20 + i * 12
    off = plt[4] + 20 + i * 12
    g[off:off + 8] = RET
    g[off + 8:off + 12] = NOP
    patched.append((nm, hex(addr), hex(off)))

print('patched', patched)
open(DST, 'wb').write(bytes(g))
print('wrote', DST, len(g))
