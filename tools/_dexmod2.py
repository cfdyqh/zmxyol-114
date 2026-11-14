import sys, struct
sys.path.insert(0, r'v11')
import dexlib

src = r'<tmp>/appdex-orig.dex'
dst = r'<tmp>/appdex-mod.dex'
dx = dexlib.Dex(src)
# find zmxyApplication.onCreate and flip its final return-void -> return-void-barrier
for (m, code_off, acc) in dx.methods('org/yjmobile/zmxy/zmxyApplication'):
    if m != 'onCreate':
        continue
    regs, ins, outs, tries, off, size = dx.code(code_off)
    assert tries == 0
    # scan backwards for 0x0e (return-void)
    for i in range(size - 1, -1, -1):
        w = struct.unpack_from('<H', dx.d, off + i * 2)[0]
        if w == 0x0e:
            struct.pack_into('<H', dx.d, off + i * 2, 0x0f)
            print('patched return-void at unit', i)
            break
    else:
        raise SystemExit('no return-void')
dx.save(dst)
print('saved', dst)
