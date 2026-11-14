"""Repack 造梦西游OL v8.0.0 APK for offline modding.

Options:
  --src          source apk
  --out          output apk
  --app-class    replace android:name on <application> (in-place AXML string edit)
  --drop-shell   remove SecNeo/Bangcle shell members (libDexHelper*, classes.dgc, classes0.jar)
  --replace-dex  replace classes.dex with given dex file (unpacked app dex)
  --add-dex      add extra dex as classesN.dex (N = next free)
  --min-sdk      rewrite uses-sdk minSdkVersion

The output keeps every entry's compression (resources.arsc stays STORED) so that
aapt/apksigner accept it.
"""
import argparse
import os
import struct
import sys
import zipfile

SHELL_ENTRIES = [
    'lib/armeabi/libDexHelper.so',
    'lib/armeabi/libDexHelper-x86.so',
    'assets/classes.dgc',
    'assets/classes0.jar',
]


# --------------------------------------------------------------------- AXML
def patch_axml(data, old_name, new_name, new_min_sdk=None):
    d = bytearray(data)
    assert struct.unpack_from('<I', d, 0)[0] == 0x00080003, 'not AXML'

    POOL = 8
    ptype, phsize, psize = struct.unpack_from('<HHI', d, POOL)
    assert ptype == 0x0001, 'first chunk is not a string pool'
    scount, stcount, flags, sstart, stystart = struct.unpack_from('<IIIII', d, POOL + 8)
    utf8 = (flags & 0x100) != 0
    assert not utf8, 'UTF-8 pool not supported'
    offsets = list(struct.unpack_from('<%dI' % scount, d, POOL + 28))
    base = POOL + sstart

    def read_str(idx):
        o = base + offsets[idx]
        n, = struct.unpack_from('<H', d, o)
        p = o + 2
        if n & 0x8000:
            n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', d, p)[0]
            p += 2
        return d[p:p + n * 2].decode('utf-16-le', 'replace'), p - o, n * 2

    strings = [read_str(i)[0] for i in range(scount)]
    index = {}
    for i, s in enumerate(strings):
        index.setdefault(s, i)

    # --- chunk helpers
    def chunks():
        off = 8
        while off + 8 <= len(d):
            t, hs, sz = struct.unpack_from('<HHI', d, off)
            if sz == 0:
                break
            yield t, off, sz
            off += sz

    def attrs(o):
        start, size = struct.unpack_from('<HH', d, o + 24)
        count, = struct.unpack_from('<H', d, o + 28)
        return [o + 16 + start + k * size for k in range(count)]

    # --- attribute name indices resolve through the resource map chunk
    resmap = {}
    android_ns_idx = None
    for t, o, sz in chunks():
        if t == 0x0180:                     # RES_XML_RESOURCE_MAP
            for i in range((sz - 8) // 4):
                rid, = struct.unpack_from('<I', d, o + 8 + 4 * i)
                resmap[i] = rid
    for i, s in enumerate(strings):
        if s == 'htt<path>':
            android_ns_idx = i

    def name_idx_for(rid):
        for i, r in resmap.items():
            if r == rid:
                return i
        return None

    IDX_NAME = name_idx_for(0x01010003)      # android:name
    assert IDX_NAME is not None, 'android:name not found in resource map'
    assert android_ns_idx is not None, 'android namespace missing'

    # --- locate the <application> element and a donor attribute slot on it
    app_slot = None
    sdk_slot = None
    loc_slot = None
    for t, o, sz in chunks():
        if t != 0x0102:
            continue
        ns_i, name_i = struct.unpack_from('<II', d, o + 16)
        tag = strings[name_i]
        if tag == 'application':
            for a in attrs(o):
                aidx, = struct.unpack_from('<I', d, a + 4)
                aname = strings[aidx]
                if aname == 'name' and d[a + 15] == 0x03:
                    app_slot = a
                if aname == 'allowBackup' and app_slot is None:
                    app_slot = a               # donor slot: application has no name
        elif tag == 'uses-sdk':
            for a in attrs(o):
                aidx, = struct.unpack_from('<I', d, a + 4)
                if strings[aidx] == 'minSdkVersion':
                    sdk_slot = a
        elif tag == 'manifest':
            # 原包写的是 android:installLocation="preferExternal"。有的模拟器实例
            # 外置存储的 SELinux 标签有问题，pm install 会直接失败：
            #   INSTALL_FAILED_MEDIA_UNAVAILABLE: Failed to restorecon
            # 改成 internalOnly(1)，装到 /data 里就没这回事了。
            for a in attrs(o):
                aidx, = struct.unpack_from('<I', d, a + 4)
                if strings[aidx] == 'installLocation':
                    loc_slot = a

    assert app_slot is not None, 'no <application> attribute slot found'

    # --- pick a string-pool slot with enough room for the new class name
    cand = index.get(old_name)
    if cand is None:
        for i, s in sorted(enumerate(strings), key=lambda kv: -len(kv[1])):
            if len(s) >= len(new_name):
                cand = i
                break
    assert cand is not None, 'no pool slot for %r' % new_name
    old, pre, blen = read_str(cand)
    total_old = pre + blen + 2
    buf = bytearray(struct.pack('<H', len(new_name))) + new_name.encode('utf-16-le') + b'\0\0'
    assert len(buf) <= total_old, 'new name too long for slot: %d > %d' % (len(buf), total_old)
    buf += b'\0' * (total_old - len(buf))
    d[base + offsets[cand]:base + offsets[cand] + total_old] = buf
    strings[cand] = new_name
    print('  axml: pool[%d] %r -> %r' % (cand, old, new_name))

    # --- rewrite the donor attribute into android:name="<new_name>"
    struct.pack_into('<I', d, app_slot + 0, android_ns_idx)   # namespace uri index
    struct.pack_into('<I', d, app_slot + 4, IDX_NAME)         # attribute name (= android:name)
    struct.pack_into('<I', d, app_slot + 8, cand)             # raw value
    d[app_slot + 15] = 0x03                                   # TYPE_STRING
    struct.pack_into('<I', d, app_slot + 16, cand)            # typed data

    if new_min_sdk is not None:
        assert sdk_slot is not None, 'no uses-sdk/minSdkVersion'
        struct.pack_into('<I', d, sdk_slot + 16, new_min_sdk)
        print('  axml: minSdkVersion -> %d' % new_min_sdk)

    if loc_slot is not None:
        struct.pack_into('<I', d, loc_slot + 8, 0xFFFFFFFF)   # raw value: none
        d[loc_slot + 15] = 0x10                               # TYPE_INT_DEC
        struct.pack_into('<I', d, loc_slot + 16, 1)           # internalOnly
        print('  axml: installLocation -> 1 (internalOnly)')

    return bytes(d)




def rename_pool_string(data, old, new):
    """Rewrite one AXML string-pool entry in place (new must not be longer)."""
    d = bytearray(data)
    assert struct.unpack_from('<I', d, 0)[0] == 0x00080003, 'not AXML'
    POOL = 8
    ptype, phsize, psize = struct.unpack_from('<HHI', d, POOL)
    assert ptype == 0x0001, 'first chunk is not a string pool'
    scount, stcount, flags, sstart, stystart = struct.unpack_from('<IIIII', d, POOL + 8)
    assert not (flags & 0x100), 'UTF-8 pool not supported'
    offsets = list(struct.unpack_from('<%dI' % scount, d, POOL + 28))
    base = POOL + sstart

    def read_str(idx):
        o = base + offsets[idx]
        n, = struct.unpack_from('<H', d, o)
        p = o + 2
        if n & 0x8000:
            n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', d, p)[0]
            p += 2
        return d[p:p + n * 2].decode('utf-16-le', 'replace'), p - o, n * 2

    for i in range(scount):
        val, pre, blen = read_str(i)
        if val != old:
            continue
        total_old = pre + blen + 2
        buf = bytearray(struct.pack('<H', len(new))) + new.encode('utf-16-le') + b'\0\0'
        assert len(buf) <= total_old, 'new name too long for slot: %d > %d' % (len(buf), total_old)
        buf += b'\0' * (total_old - len(buf))
        d[base + offsets[i]:base + offsets[i] + total_old] = buf
        print('  axml: pool[%d] %r -> %r' % (i, old, new))
        return bytes(d)
    raise AssertionError('string not in pool: %r' % old)


# --------------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--app-class')
    ap.add_argument('--old-app-class', default='com.secneo.apkwrapper.ApplicationWrapper')
    ap.add_argument('--drop-shell', action='store_true')
    ap.add_argument('--replace-dex')
    ap.add_argument('--add-dex', action='append', default=[])
    ap.add_argument('--min-sdk', type=int, default=19)
    ap.add_argument('--manifest-only', action='store_true')
    ap.add_argument('--asset', action='append', default=[], metavar='NAME=PATH')
    ap.add_argument('--rename-string', action='append', default=[], metavar='OLD=NEW')
    a = ap.parse_args()
    assets = {}
    for spec in a.asset:
        nm, _, path = spec.partition('=')
        assets[nm] = path

    zin = zipfile.ZipFile(a.src)
    names = zin.namelist()
    if a.replace_dex:
        assert 'classes.dex' in names
    extra = {}
    if a.add_dex:
        n = 2
        while 'classes%d.dex' % n in names:
            n += 1
        for i, d in enumerate(a.add_dex):
            extra['classes%d.dex' % (n + i)] = d

    # --asset entries that do not exist yet in the source APK are appended
    # (previously --asset could only override existing names, which silently
    #  dropped newly added libs such as libgadget.so).
    for nm, path in assets.items():
        if nm not in names:
            extra[nm] = path

    with zipfile.ZipFile(a.out, 'w', allowZip64=True) as zout:
        for info in zin.infolist():
            nm = info.filename
            if a.drop_shell and nm in SHELL_ENTRIES:
                print('  drop', nm)
                continue
            data = zin.read(nm)
            if nm in assets:
                data = open(assets[nm], 'rb').read()
                print('  override %s <- %s (%d bytes)' % (nm, assets[nm], len(data)))
                info = zipfile.ZipInfo(nm, info.date_time)
                info.compress_type = zipfile.ZIP_DEFLATED
            if nm == 'AndroidManifest.xml' and a.app_class:
                data = patch_axml(data, a.old_app_class,
                                  a.app_class, a.min_sdk)
                info = zipfile.ZipInfo(nm, info.date_time)
                info.compress_type = zipfile.ZIP_DEFLATED
            if nm == 'AndroidManifest.xml':
                for spec in a.rename_string:
                    oval, _, nval = spec.partition('=')
                    data = rename_pool_string(data, oval, nval)
                if a.rename_string:
                    info = zipfile.ZipInfo(nm, info.date_time)
                    info.compress_type = zipfile.ZIP_DEFLATED
            elif nm == 'classes.dex' and a.replace_dex:
                data = open(a.replace_dex, 'rb').read()
                print('  replace classes.dex with %s (%d bytes)' % (a.replace_dex, len(data)))
                info = zipfile.ZipInfo(nm, info.date_time)
                info.compress_type = zipfile.ZIP_DEFLATED
            zi = zipfile.ZipInfo(nm, info.date_time)
            zi.compress_type = info.compress_type
            if nm == 'resources.arsc':
                zi.compress_type = zipfile.ZIP_STORED
            zi.external_attr = info.external_attr
            zout.writestr(zi, data)
        for nm, path in extra.items():
            data = open(path, 'rb').read()
            zi = zipfile.ZipInfo(nm, (2020, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(zi, data)
            print('  add %s (%d bytes)' % (nm, len(data)))
    print('wrote', a.out, os.path.getsize(a.out))


if __name__ == '__main__':
    main()
