import struct, zlib, sys, os, time

def raw_zip_swap(src, dst, replacements, drop_prefixes=('META-INF/',)):
    data = open(src, 'rb').read()
    # locate EOCD
    eocd = data.rfind(b'PK\x05\x06')
    if eocd < 0: raise SystemExit('no eocd')
    (disk, cd_disk, n_disk, n_total, cd_size, cd_off, comment_len) = struct.unpack_from('<HHHHIIH', data, eocd+4)
    if n_disk != n_total or cd_disk != 0 or disk != 0: raise SystemExit('multi-disk zip unsupported')
    if cd_off == 0xffffffff or n_total == 0xffff: raise SystemExit('zip64 unsupported')
    print('entries', n_total, 'cd_off', cd_off, 'cd_size', cd_size)
    out = bytearray()
    central = []
    p = cd_off
    done = 0
    t0 = time.time()
    for i in range(n_total):
        if data[p:p+4] != b'PK\x01\x02': raise SystemExit('bad central at %d' % p)
        (ver_made, ver_need, flags, method, mtime, mdate, crc, csize, usize,
         fnlen, extralen, commentlen, diskstart, iattr, eattr, localoff) = struct.unpack_from('<HHHHHHIIIHHHHHII', data, p+4)
        name = data[p+46:p+46+fnlen].decode('utf-8', 'replace')
        if i < 3:
            print('DBG', i, 'p', p, 'fnlen', fnlen, 'name', repr(name), 'lo', localoff, flush=True)
        extra = data[p+46+fnlen:p+46+fnlen+extralen]
        comment = data[p+46+fnlen+extralen:p+46+fnlen+extralen+commentlen]
        p += 46 + fnlen + extralen + commentlen
        if any(name.startswith(x) for x in drop_prefixes):
            continue
        # local header
        if data[localoff:localoff+4] != b'PK\x03\x04':
            raise SystemExit('bad local %d %r off=%d sig=%s' % (i, name, localoff, data[localoff:localoff+8].hex()))
        (lver, lflags, lmethod, lt, ld, lcrc, lcsize, lusize, lfnlen, lextralen) = struct.unpack_from('<HHHHHIIIHH', data, localoff+4)
        lname = data[localoff+30:localoff+30+lfnlen]
        lextra = data[localoff+30+lfnlen:localoff+30+lfnlen+lextralen]
        dstart = localoff + 30 + lfnlen + lextralen
        if name in replacements:
            raw = replacements[name]
            c = zlib.compressobj(9, zlib.DEFLATED, -15)
            comp = c.compress(raw) + c.flush()
            crc = zlib.crc32(raw) & 0xffffffff
            csize = len(comp); usize = len(raw); method = 8
            payload = comp
        else:
            payload = data[dstart:dstart+csize]
        # strip data descriptor flag, write local header
        flags2 = flags & ~0x08
        localoff2 = len(out)
        out += struct.pack('<IHHHHHIIIHH', 0x04034b50, ver_need, flags2, method, mtime, mdate, crc, csize, usize, fnlen, 0)
        out += name.encode('utf-8')
        out += payload
        central.append((ver_made, ver_need, flags2, method, mtime, mdate, crc, csize, usize,
                        name.encode('utf-8'), b'', b'', diskstart, iattr, eattr, localoff2))
        done += 1
        if done % 1000 == 0:
            print('copied', done, 'size', len(out), '%.1fs' % (time.time()-t0), flush=True)
    cd_start = len(out)
    for (ver_made, ver_need, flags, method, mtime, mdate, crc, csize, usize, nb, ex, cm, ds, ia, ea, lo) in central:
        out += struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, ver_made, ver_need, flags, method, mtime, mdate,
                           crc, csize, usize, len(nb), len(ex), len(cm), ds, ia, ea, lo)
        out += nb + ex + cm
    cd_size = len(out) - cd_start
    out += struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, len(central), len(central), cd_size, cd_start, 0)
    open(dst, 'wb').write(out)
    print('wrote', dst, len(out), 'entries', len(central), '%.1fs' % (time.time()-t0))

if __name__ == '__main__':
    src, dst, dex = sys.argv[1], sys.argv[2], sys.argv[3]
    repl = {'classes.dex': open(dex, 'rb').read()}
    if len(sys.argv) > 4 and sys.argv[4]:
        repl['classes2.dex'] = open(sys.argv[4], 'rb').read()
    # 第 5 个参数：可选的 assets/database/magic.sdb（改法宝孔数用）
    if len(sys.argv) > 5 and sys.argv[5]:
        repl['assets/database/magic.sdb'] = open(sys.argv[5], 'rb').read()
        print('swap magic.sdb from', sys.argv[5])
    raw_zip_swap(src, dst, repl)
