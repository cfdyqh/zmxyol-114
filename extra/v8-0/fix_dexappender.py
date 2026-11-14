# -*- coding: utf-8 -*-
"""DexAppender 尾部：jadx 掉了 return，补一个 null 返回。"""
import io

P = r"<tmp>/src\sources\org\yjmobile\zmxy\offline\DexAppender.java"
with io.open(P, encoding="utf-8") as f:
    src = f.read()

bad = """            java.lang.Throwable th = th3;
            zipFile = null;
            inputStream = null;
        }
    }"""
good = """            java.lang.Throwable th = th3;
            zipFile = null;
            inputStream = null;
        }
        return null;
    }"""
assert bad in src, "pattern not found"
src = src.replace(bad, good, 1)
with io.open(P, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("ok")
