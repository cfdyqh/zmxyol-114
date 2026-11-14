import struct, hashlib, zlib, sys, shutil

class Dex:
    def __init__(self, data):
        self.d = data
        (self.string_ids_size, self.string_ids_off,
         self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off,
         self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off) = struct.unpack_from('<12I', data, 56)
    def u32(self, off): return struct.unpack_from('<I', self.d, off)[0]
    def string(self, idx):
        off = self.u32(self.string_ids_off + idx*4)
        p = off
        while self.d[p] & 0x80: p += 1
        p += 1
        end = self.d.index(b'\0', p)
        return self.d[p:end].decode('utf-8','replace')
    def type_str(self, idx):
        return self.string(self.u32(self.type_ids_off + idx*4))
    def proto(self, idx):
        off=self.proto_ids_off+idx*12
        ret=self.u32(off+4); params_off=self.u32(off+8)
        params=[]
        if params_off:
            n=self.u32(params_off)
            params=[struct.unpack_from('<H', self.d, params_off+4+i*2)[0] for i in range(n)]
        return None,[self.type_str(t) for t in params]
    def method(self, idx):
        off=self.method_ids_off+idx*8
        cls,proto,name=struct.unpack_from('<HHI', self.d, off)
        return self.type_str(cls), self.proto(proto), self.string(name)
    def class_def(self, name):
        target='L'+name+';'
        for i in range(self.class_defs_size):
            off=self.class_defs_off+i*32
            if self.type_str(self.u32(off))==target:
                return off
        return None

def uleb(data,p):
    r=0;sh=0
    while True:
        b=data[p];p+=1;r|=(b&0x7f)<<sh;sh+=7
        if not (b&0x80): return r,p

def patch(dex_path, out_path, cls_name, method_name, arg_count):
    d=bytearray(open(dex_path,'rb').read())
    dx=Dex(d)
    cd=dx.class_def(cls_name)
    if cd is None: raise SystemExit('class not found')
    cd_off=dx.u32(cd+24)
    p=cd_off
    sf,p=uleb(d,p); inf,p=uleb(d,p); dm,p=uleb(d,p); vm,p=uleb(d,p)
    idx=0
    for _ in range(sf+inf):
        diff,p=uleb(d,p); acc,p=uleb(d,p); idx+=diff
    idx=0; found=None
    for _ in range(dm+vm):
        diff,p=uleb(d,p); acc,p=uleb(d,p); code,p=uleb(d,p); idx+=diff
        c,proto,name=dx.method(idx)
        if name==method_name:
            found=(idx,acc,code,proto)
            break
    if not found: raise SystemExit('method not found')
    idx,acc,code,proto=found
    regs,ins,outs,tries,dbg,insns_size=struct.unpack_from('<4H2I', d, code)
    print('method', method_name, 'code_off', code, 'insns_size', insns_size, 'regs', regs, 'ins', ins)
    old=struct.unpack_from('<%dH'%insns_size, d, code+16)
    print('old', ' '.join('%04x'%x for x in old))
    # const/4 v0, #0 ; return v0 ; nop...
    new=[0x0012, 0x000f] + [0x0000]*(insns_size-2)
    struct.pack_into('<%dH'%insns_size, d, code+16, *new)
    # fix header: signature (bytes 12..32) = sha1(bytes[32:]); checksum = adler32(bytes[12:])
    sig=hashlib.sha1(bytes(d[32:])).digest()
    d[12:32]=sig
    chk=zlib.adler32(bytes(d[12:])) & 0xffffffff
    struct.pack_into('<I', d, 8, chk)
    open(out_path,'wb').write(d)
    print('wrote', out_path, len(d), 'checksum', hex(chk))

if __name__=='__main__':
    patch(sys.argv[1], sys.argv[2], 'org/cocos2dx/lib/Cocos2dxHelper', 'playEffect', 3)
