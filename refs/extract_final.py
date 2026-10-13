#!/usr/bin/env python3
# -*- coding: utf-8 -*-
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

def show(label, pat, before=500, after=1400, limit=2):
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

show("人脸验证 faceImg 用法", r"faceImg")
show("开始跑步 startSport 调用点", r"startSport\(", before=300, after=1800, limit=1)
show("跑步结束 sport/end", r"sport/end", before=800, after=1200, limit=1)
show("瞬移检测 pointIsFly", r"pointIsFly\s*[:=]\s*function", before=100, after=1200, limit=1)
show("区域检测 isInPoints", r"isInPoints\s*[:=]\s*function", before=100, after=1200, limit=1)
show("上传 uploadPointList", r"uploadPointList\s*[:=]\s*function", before=100, after=900, limit=1)
