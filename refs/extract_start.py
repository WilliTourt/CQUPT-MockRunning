#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""定位 start2 / end 的请求体构造，以及 sportRecordNo 的来源。"""
import os, re
import os
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

ROOT = os.path.join(HERE, "unpacked")
blobs = {}
for dp, _, ns in os.walk(ROOT):
    for n in ns:
        if n.endswith(".js"):
            with open(os.path.join(dp, n), "r", encoding="utf-8", errors="replace") as f:
                blobs[os.path.relpath(os.path.join(dp, n), ROOT)] = f.read()

def ctx(label, pat, before=1200, after=2200, limit=3):
    print("=" * 78)
    print("###", label)
    print("=" * 78)
    n = 0
    for f, s in blobs.items():
        for m in re.finditer(pat, s):
            if n >= limit:
                return
            a = max(0, m.start() - before)
            b = m.start() + after
            print(f"[{f} @{m.start()}]")
            print(s[a:b])
            print()
            n += 1

ctx("startSport 调用（请求体构造）", r"\.startSport\(", before=200, after=1800, limit=2)
ctx("sportRecordNo 赋值来源", r"sportRecordNo\s*[:=]\s*[a-zA-Z0-9_.\[\]]{0,40}(?=,|;|\})",
    before=800, after=600, limit=6)
ctx("endSport 调用", r"\.endSport\(", before=600, after=900, limit=2)
ctx("sportType", r"sportType", before=300, after=300, limit=4)
