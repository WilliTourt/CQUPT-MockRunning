#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import os, re, collections
import os
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

ROOT = os.path.join(HERE, "unpacked")
blobs = {}
for dp, _, ns in os.walk(ROOT):
    for n in ns:
        if n.endswith(".js"):
            p = os.path.join(dp, n)
            with open(p, "r", encoding="utf-8", errors="replace") as f:
                blobs[os.path.relpath(p, ROOT)] = f.read()

print("=" * 78)
print("A) 页面清单（按 define 的 route）")
routes = set()
for f, s in blobs.items():
    for m in re.finditer(r'define\("(pages/[^"]+)"', s):
        routes.add(m.group(1))
for r in sorted(routes):
    print("   ", r)

print()
print("=" * 78)
print("B) 轨迹点对象的字段名（出现 latitude: 或 longitude: 的对象字面量）")
keys = collections.Counter()
pat = re.compile(r'\{[^{}]{0,300}?(?:latitude|longitude)\s*:[^{}]{0,300}?\}')
for f, s in blobs.items():
    for m in pat.finditer(s):
        keys[m.group(0)[:260]] += 1
for k, c in keys.most_common(12):
    print(f"   {c}x  {k}\n")

print("=" * 78)
print("C) 调用 startSport / uploadPoints 的地方（请求体来源）")
for kw in ("startSport", "uploadPoints(", "saveListByNo"):
    print(f"\n--- {kw} ---")
    shown = 0
    for f, s in blobs.items():
        for m in re.finditer(re.escape(kw), s):
            if shown >= 4:
                break
            a = max(0, m.start() - 900)
            b = m.start() + 900
            print(f"[{f}] ...{s[a:b]}...".replace("\n", " "))
            print()
            shown += 1

print("=" * 78)
print("D) 配速 / 距离 / 校验相关片段")
for kw in ("pace", "notInPlayCount", "pointCount"):
    print(f"\n--- {kw} ---")
    shown = 0
    for f, s in blobs.items():
        for m in re.finditer(r'[^A-Za-z]' + re.escape(kw) + r'[^A-Za-z]', s):
            if shown >= 3:
                break
            a = max(0, m.start() - 350)
            b = m.start() + 450
            print(f"[{f}] ...{s[a:b]}...".replace("\n", " "))
            print()
            shown += 1
