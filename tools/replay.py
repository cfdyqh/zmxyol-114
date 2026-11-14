"""Replay a captured request against the real game server and decrypt the reply.

Reads one JSON line from v11/requests.log (by action, default login), re-encodes
it with the 11.4.0 codec and POSTs it to http<path>
"""
import json
import ssl
import sys
import urllib.request

sys.path.insert(0, r'<path>')
import codec

URL = 'http<path>'
LOG = r'<path>'


def find(action):
    best = None
    for line in open(LOG, encoding='utf-8', errors='replace'):
        line = line.strip()
        if not line:
            continue
        try:
            j = json.loads(line)
        except Exception:
            continue
        if j.get('action') == action:
            best = j
    return best


def main():
    action = sys.argv[1] if len(sys.argv) > 1 else 'login'
    j = find(action)
    if not j:
        print('no %r in %s' % (action, LOG))
        return
    body = codec.encode(json.dumps(j, separators=(',', ':'), ensure_ascii=False))
    print('request %d bytes, action=%s' % (len(body), action))
    ctx = ssl.create_default_context()
    req = urllib.request.Request(URL, data=body, headers={
        'Content-Type': 'application/json', 'Host': 'zmol.4399sj.com'})
    try:
        raw = urllib.request.urlopen(req, timeout=20, context=ctx).read()
    except Exception as e:
        print('POST failed: %r' % (e,))
        return
    print('reply %d bytes: %r' % (len(raw), raw[:60]))
    try:
        print('PLAIN:', codec.decode(raw).decode('utf-8', 'replace'))
    except Exception as e:
        print('decode failed: %r' % (e,))


if __name__ == '__main__':
    main()
