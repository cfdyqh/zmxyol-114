"""Minimal DEX reader/writer helpers for surgical method patching."""
import struct, hashlib, zlib


class Dex:
    def __init__(self, path):
        self.path = path
        self.d = bytearray(open(path, 'rb').read())
        self._parse()

    def _parse(self):
        d = self.d
        self.string_ids_size, self.string_ids_off = struct.unpack_from('<II', d, 0x38)
        self.type_ids_size, self.type_ids_off = struct.unpack_from('<II', d, 0x40)
        self.proto_ids_size, self.proto_ids_off = struct.unpack_from('<II', d, 0x48)
        self.method_ids_size, self.method_ids_off = struct.unpack_from('<II', d, 0x58)
        self.class_defs_size, self.class_defs_off = struct.unpack_from('<II', d, 0x60)

    # ---- primitive readers
    @staticmethod
    def uleb(d, o):
        r = 0
        s = 0
        while True:
            b = d[o]
            o += 1
            r |= (b & 0x7f) << s
            if not (b & 0x80):
                return r, o
            s += 7

    def string(self, idx):
        off = struct.unpack_from('<I', self.d, self.string_ids_off + idx * 4)[0]
        n, o = self.uleb(self.d, off)
        return self.d[o:o + n].decode('utf-8', 'replace')

    def type_name(self, idx):
        sidx = struct.unpack_from('<I', self.d, self.type_ids_off + idx * 4)[0]
        return self.string(sidx)

    def method_info(self, idx):
        cls, proto, name = struct.unpack_from('<HHI', self.d, self.method_ids_off + idx * 8)
        return self.type_name(cls), self.string(name)

    def class_def(self, name):
        want = 'L' + name + ';'
        for i in range(self.class_defs_size):
            o = self.class_defs_off + i * 32
            class_idx, access, super_idx = struct.unpack_from('<III', self.d, o)
            if self.type_name(class_idx) == want:
                cdata_off = struct.unpack_from('<I', self.d, o + 24)[0]
                return {'idx': i, 'off': o, 'access': access, 'cdata_off': cdata_off}
        raise KeyError(name)

    def methods(self, name):
        """-> list of (method_name, code_off, access)"""
        cd = self.class_def(name)
        out = []
        if cd['cdata_off'] == 0:
            return out
        d = self.d
        o = cd['cdata_off']
        sf, o = self.uleb(d, o)
        inf, o = self.uleb(d, o)
        dm, o = self.uleb(d, o)
        vm, o = self.uleb(d, o)
        midx = 0
        for _ in range(sf + inf):
            _, o = self.uleb(d, o)
            _, o = self.uleb(d, o)
        for count in (dm, vm):
            midx = 0
            for _ in range(count):
                diff, o = self.uleb(d, o)
                acc, o = self.uleb(d, o)
                code_off, o = self.uleb(d, o)
                midx += diff
                _, mname = self.method_info(midx)
                out.append((mname, code_off, acc))
        return out

    def code(self, code_off):
        """-> (registers, ins, outs, tries, insns_off, insns_size)"""
        d = self.d
        regs, ins, outs, tries = struct.unpack_from('<HHHH', d, code_off)
        insns_size = struct.unpack_from('<I', d, code_off + 12)[0]
        return regs, ins, outs, tries, code_off + 16, insns_size

    def fix_checksums(self):
        d = self.d
        d[12:32] = hashlib.sha1(bytes(d[32:])).digest()
        struct.pack_into('<I', d, 8, zlib.adler32(bytes(d[12:])) & 0xffffffff)

    def save(self, path):
        self.fix_checksums()
        open(path, 'wb').write(bytes(self.d))


def dump_insns(dex, name, mname, limit=80):
    for (m, code_off, acc) in dex.methods(name):
        if m != mname:
            continue
        regs, ins, outs, tries, off, size = dex.code(code_off)
        print('method %s.%s regs=%d ins=%d outs=%d tries=%d size=%d @0x%x'
              % (name, mname, regs, ins, outs, tries, size, code_off))
        words = struct.unpack_from('<%dH' % min(size, limit), dex.d, off)
        print(' '.join('%04x' % w for w in words))
        return
    raise KeyError(mname)


if __name__ == '__main__':
    import sys
    dx = Dex(sys.argv[1])
    for m, c, a in dx.methods(sys.argv[2]):
        print('%s code=0x%x acc=0x%x' % (m, c, a))
