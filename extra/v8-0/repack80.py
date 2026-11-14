# -*- coding: utf-8 -*-
"""把编出来的 classes.dex 换进 8.0 APK（mod dex 在 8.0 里就是 classes.dex）。"""
import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from _zipswap import raw_zip_swap  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--dex", required=True)
    a = ap.parse_args()
    raw = open(a.dex, "rb").read()
    print("swap classes.dex %d bytes" % len(raw))
    raw_zip_swap(a.src, a.out, {"classes.dex": raw})
    print("ok", a.out, os.path.getsize(a.out))


if __name__ == "__main__":
    main()
