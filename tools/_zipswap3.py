"""Raw zip rewriter: copy every entry, replacing the named ones.

usage: python _zipswap3.py <src.apk> <dst.apk> name=path [name=path ...]

Entries under META-INF/ are dropped (the APK gets re-signed). Replaced entries
are deflated fresh; everything else keeps its original compressed bytes, so this
is ~3 s for a 700 MB APK.
"""
import struct
import sys
import time
import zlib


def raw_zip_swap(src, dst, replacements, drop_prefixes=('META-INF/',)):
    data = open(src, 'rb').read()
    eocd = data.rfind(b'PK\x05\x06')
    if eocd < 0:
        raise SystemExit('no eocd')
    (disk, cd_disk, n_disk, n_total, cd_size, cd_off, comment_len) = \
        struct.unpack_from('<HHHHIIH', data, eocd + 4)
    if n_disk != n_total or cd_disk != 0 or disk != 0:
        raise SystemExit('multi-disk zip unsupported')
    if cd_off == 0xffffffff or n_total == 0xffff:
        raise SystemExit('zip64 unsupported')
    print('entries', n_total)
    out = bytearray()
    central = []
    p = cd_off
    done = 0
    t0 = time.time()
    replaced = set()
    for i in range(n_total):
        if data[p:p + 4] != b'PK\x01\x02':
            raise SystemExit('bad central at %d' % p)
        (ver_made, ver_need, flags, method, mtime, mdate, crc, csize, usize,
         fnlen, extralen, commentlen, diskstart, iattr, eattr, localoff) = \
            struct.unpack_from('<HHHHHHIIIHHHHHII', data, p + 4)
        name = data[p + 46:p + 46 + fnlen].decode('utf-8', 'replace')
        p += 46 + fnlen + extralen + commentlen
        if any(name.startswith(x) for x in drop_prefixes):
            continue
        if data[localoff:localoff + 4] != b'PK\x03\x04':
            raise SystemExit('bad local %r' % name)
        (lver, lflags, lmethod, lt, ld, lcrc, lcsize, lusize, lfnlen, lextralen) = \
            struct.unpack_from('<HHHHHIIIHH', data, localoff + 4)
        dstart = localoff + 30 + lfnlen + lextralen
        if name in replacements:
            raw = replacements[name]
            c = zlib.compressobj(9, zlib.DEFLATED, -15)
            comp = c.compress(raw) + c.flush()
            crc = zlib.crc32(raw) & 0xffffffff
            csize, usize, method = len(comp), len(raw), 8
            payload = comp
            replaced.add(name)
            print('  replace %s (%d bytes)' % (name, len(raw)))
        else:
            payload = data[dstart:dstart + csize]
        flags2 = flags & ~0x08
        localoff2 = len(out)
        out += struct.pack('<IHHHHHIIIHH', 0x04034b50, ver_need, flags2, method, mtime, mdate,
                           crc, csize, usize, fnlen, 0)
        out += name.encode('utf-8')
        out += payload
        central.append((ver_made, ver_need, flags2, method, mtime, mdate, crc, csize, usize,
                        name.encode('utf-8'), diskstart, iattr, eattr, localoff2))
        done += 1
        if done % 4000 == 0:
            print('  copied', done, '%.1fs' % (time.time() - t0), flush=True)
    cd_start = len(out)
    for (ver_made, ver_need, flags, method, mtime, mdate, crc, csize, usize, nb, ds, ia, ea, lo) in central:
        out += struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, ver_made, ver_need, flags, method,
                           mtime, mdate, crc, csize, usize, len(nb), 0, 0, ds, ia, ea, lo)
        out += nb
    cd_size = len(out) - cd_start
    out += struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, len(central), len(central), cd_size, cd_start, 0)
    open(dst, 'wb').write(bytes(out))
    missing = set(replacements) - replaced
    if missing:
        print('  WARNING: not found in zip:', sorted(missing))
    print('wrote', dst, len(out), 'entries', len(central), '%.1fs' % (time.time() - t0))


if __name__ == '__main__':
    src, dst = sys.argv[1], sys.argv[2]
    repl = {}
    for spec in sys.argv[3:]:
        name, _, path = spec.partition('=')
        repl[name] = open(path, 'rb').read()
    raw_zip_swap(src, dst, repl)
