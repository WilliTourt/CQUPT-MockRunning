#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把关键接口的调用点上下文按大窗口抠出来，还原请求体结构。"""
import os, re
import os
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

ROOT = os.path.join(HERE, "unpacked")
OUT = os.path.join(HERE, "flow_dump.txt")

blobs = {}
for dirpath, _, names in os.walk(ROOT):
    for n in names:
        if n.endswith(".js"):
            p = os.path.join(dirpath, n)
            with open(p, "r", encoding="utf-8", errors="replace") as f:
                blobs[os.path.relpath(p, ROOT)] = f.read()

TARGETS = [
    ("START2", r"sport/start2"),
    ("UPLOADPOINTS", r"uploadPoints"),
    ("UPLOADJSON", r"uploadPointsJson"),
    ("SAVELIST", r"saveListByNo"),
    ("SPORT_END", r"sport/end"),
    ("SPORT_CONTINUE", r"sport/continue"),
    ("CHECKBIND", r"checkBinding"),
    ("POINTLIST", r"sportPoint/listAll"),
    ("RECORDINFO", r"sportRecord/info"),
]

with open(OUT, "w", encoding="utf-8") as out:
    for tag, pat in TARGETS:
        out.write("\n" + "=" * 78 + f"\n### {tag}   /{pat}\n" + "=" * 78 + "\n")
        found = 0
        for f, s in blobs.items():
            for m in re.finditer(pat, s):
                found += 1
                if found > 3:
                    break
                a = max(0, m.start() - 700)
                b = m.start() + 1500
                out.write(f"\n----- [{f}] @{m.start()} -----\n")
                out.write(s[a:b] + "\n")
        out.write(f"\n(命中 {found} 处)\n")

print("写出 ->", OUT, os.path.getsize(OUT), "字节\n")

# 直接打印 start2 与 uploadPoints 的核心段
for tag, pat in [("START2", r"sport/start2"), ("UPLOADPOINTS", r"uploadPoints")]:
    print("=" * 78)
    print(f"### {tag}")
    print("=" * 78)
    for f, s in blobs.items():
        for m in re.finditer(pat, s):
            a = max(0, m.start() - 600)
            b = m.start() + 1200
            print(s[a:b])
            print("\n--- cut ---\n")
            break
        else:
            continue
        break
