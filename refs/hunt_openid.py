#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
在真实微信缓存里定位 openid。
不依赖任何解密：开放明文搜索 + 明文候选提取。
"""
import os, re, sys

ROOT = os.path.expandvars(r"%APPDATA%\Tencent\xwechat")
print("根目录:", ROOT, os.path.isdir(ROOT))
if not os.path.isdir(ROOT):
    sys.exit(1)

# openid 形态：18~32 位，[A-Za-z0-9_-]，通常以字母 o 开头
PAT_OPENID = re.compile(rb"\bo[A-Za-z0-9_\-]{20,40}\b")
KEYWORDS = [b"openid", b"openId", b"OPENID", b"unionid", b"unifyId", b"token",
            b"studentNo", b"sport.cqupt", b"new_wxapp", b"checkBinding"]

total_files = 0
total_bytes = 0
hits = []
kw_hits = {}

SKIP_EXT = {".exe", ".dll", ".pak", ".so", ".bin"}

for dp, dns, fns in os.walk(ROOT):
    for fn in fns:
        p = os.path.join(dp, fn)
        try:
            sz = os.path.getsize(p)
        except OSError:
            continue
        total_files += 1
        total_bytes += sz
        if sz > 40 * 1024 * 1024:
            continue
        # 跳过明显的二进制
        if os.path.splitext(fn)[1].lower() in SKIP_EXT:
            continue
        try:
            with open(p, "rb") as f:
                b = f.read()
        except OSError:
            continue

        rel = os.path.relpath(p, ROOT)

        for kw in KEYWORDS:
            if kw in b:
                kw_hits.setdefault(kw.decode("ascii", "replace"), []).append((rel, b.count(kw)))

        if b"openid" in b:
            for m in PAT_OPENID.finditer(b):
                hits.append((rel, m.start(), m.group().decode("ascii", "replace")))

print(f"\n扫描 {total_files:,} 个文件 / {total_bytes/1024/1024:.1f} MB\n")

print("=" * 78)
print("1) 关键词命中")
print("=" * 78)
for k, v in sorted(kw_hits.items(), key=lambda x: -len(x[1])):
    print(f"\n--- {k}  命中 {len(v)} 个文件 ---")
    for rel, c in v[:12]:
        print(f"    [{c:>3}x] {rel}")

print()
print("=" * 78)
print("2) openid 形态的候选串")
print("=" * 78)
seen = set()
for rel, off, s in hits:
    if s in seen:
        continue
    seen.add(s)
    print(f"   {s}    <- {rel} @0x{off:x}")
if not hits:
    print("   （未找到）")

print()
print("=" * 78)
print("3) 含 openid 字面量的文件，逐个 dump 上下文")
print("=" * 78)
done = set()
for rel, off, s in hits:
    if rel in done:
        continue
    done.add(rel)
    p = os.path.join(ROOT, rel)
    with open(p, "rb") as f:
        b = f.read()
    i = b.find(b"openid")
    lo, hi = max(0, i - 400), min(len(b), i + 900)
    t = "".join(chr(c) if 32 <= c < 127 else "\u00b7" for c in b[lo:hi])
    print(f"\n########## {rel}  ({len(b):,} B) ##########")
    print(t)
