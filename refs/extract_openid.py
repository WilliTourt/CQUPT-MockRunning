#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 Chromium simple-cache 里抠出 openid / unifyId / studentNo。"""
import os, re

ROOT = os.path.expandvars(r"%APPDATA%\Tencent\xwechat")
PROF = os.path.join(ROOT, "radium", "web", "profiles")

CACHE_FILES = []
for dp, dns, fns in os.walk(PROF):
    if "Cache_Data" in dp or dp.endswith("Cache"):
        for fn in fns:
            CACHE_FILES.append(os.path.join(dp, fn))

print(f"缓存文件 {len(CACHE_FILES)} 个\n")

def show(path, needle, before=150, after=700, limit=3):
    with open(path, "rb") as f:
        b = f.read()
    n = 0
    i = 0
    while n < limit:
        i = b.find(needle, i)
        if i < 0:
            break
        lo, hi = max(0, i - before), min(len(b), i + after)
        seg = b[lo:hi]
        t = "".join(chr(c) if 32 <= c < 127 else "\u00b7" for c in seg)
        print(f"\n  @0x{i:x}  ({os.path.basename(path)}, {len(b):,} B)")
        print("  " + t.replace("\u00b7" * 4, "\u00b7\u00b7\u00b7\u00b7"))
        i += 1
        n += 1

for needle in (b"checkBinding", b"sport.cqupt", b"unifyId", b"studentNo"):
    print("=" * 78)
    print("###", needle.decode())
    print("=" * 78)
    for p in CACHE_FILES:
        try:
            with open(p, "rb") as f:
                b = f.read()
        except OSError:
            continue
        if needle in b:
            show(p, needle)

print()
print("=" * 78)
print("### openid 候选（在缓存里按形态搜）")
print("=" * 78)
PAT = re.compile(rb"[?&]openid=([A-Za-z0-9_\-]{10,40})")
PAT2 = re.compile(rb'"openid"\s*:\s*"([A-Za-z0-9_\-]{10,40})"')
found = set()
for p in CACHE_FILES:
    try:
        b = open(p, "rb").read()
    except OSError:
        continue
    for m in PAT.finditer(b):
        found.add(("query", m.group(1).decode()))
    for m in PAT2.finditer(b):
        found.add(("json", m.group(1).decode()))
if found:
    for k, v in sorted(found):
        print(f"   [{k}] {v}")
else:
    print("   （这两个形态都没直接命中；缓存可能是分块存储，看上面的上下文）")
