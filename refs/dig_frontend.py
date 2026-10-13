#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""掏出 pctool 内嵌的 Wails 前端，并定位 scanner 读的文件路径。"""
import re, os
import os
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

P = os.environ.get("COLLECTOR_EXE", os.path.join(HERE, "采集器.exe"))
OUT = HERE
data = open(P, "rb").read()

# ---------------------------------------------------------------- 1. 前端资源区域
print("=" * 78)
print("1) 定位内嵌前端")
print("=" * 78)
anchor = data.find(b"function wechatExitText")
print(f"   wechatExitText @0x{anchor:x}")

# 往前找 HTML 起点，往后找 JS 结束
start = data.rfind(b"<!DOCTYPE", 0, anchor)
if start < 0:
    start = data.rfind(b"<html", 0, anchor)
print(f"   <!DOCTYPE/{'<html'} @0x{start:x}" if start >= 0 else "   未找到 HTML 起点")

# 前端通常是连续的一大块可打印区。向后扫到熵突变
p = anchor
run_start = anchor
# 往前扫可打印区
while run_start > 0 and data[run_start - 1] in b"\r\n\t" or (32 <= data[run_start - 1] < 127):
    run_start -= 1
run_end = anchor
while run_end < len(data) and (data[run_end] in b"\r\n\t" or 32 <= data[run_end] < 127):
    run_end += 1
print(f"   连续可打印区 0x{run_start:x} .. 0x{run_end:x}  = {run_end - run_start:,} 字节")

# 整段前端可能被切成多块，扩到 ±120KB 看看
lo = max(0, anchor - 120000)
hi = min(len(data), anchor + 260000)
blob = data[lo:hi]
txt = blob.decode("utf-8", "replace")

front_path = os.path.join(OUT, "collector_frontend_raw.txt")
with open(front_path, "w", encoding="utf-8") as f:
    f.write(txt)
print(f"   -> {front_path}")

# ---------------------------------------------------------------- 2. 前端里的接口/字段
print()
print("=" * 78)
print("2) 前端里出现的接口路径与字段")
print("=" * 78)
for pat, label in [
    (r'["\'`](/[a-zA-Z0-9_/\-]{3,60})["\'`]', "引号包裹的路径"),
    (r'https?://[a-zA-Z0-9_.:/\-]{6,80}', "URL"),
    (r'\b(?:openid|credId|token|unifyId|studentNo|wxid|profile|radium|applet)\b', "字段名"),
]:
    hits = sorted(set(re.findall(pat, txt)))
    print(f"\n--- {label} ({len(hits)}) ---")
    for h in hits[:60]:
        print("   ", h)

# ---------------------------------------------------------------- 3. scanner 读的路径
print()
print("=" * 78)
print("3) scanner 相关的路径 / 文件名常量")
print("=" * 78)
for kw in [b"scanner.go", b"scanner.Scan", b"ProfileStat", b"Candidate", b"ProbeEnv",
           b"wechat.proc.go", b"pctool/internal/wechat.Running"]:
    i = data.find(kw)
    if i < 0:
        print(f"\n--- {kw.decode()} : 未找到")
        continue
    print(f"\n--- {kw.decode()} @0x{i:x} ---")
    for off in range(max(0, i - 400), min(len(data), i + 1400), 400):
        chunk = data[off:off + 400]
        t = "".join(chr(c) if 32 <= c < 127 else "\u00b7" for c in chunk)
        print("   ", t)

# ---------------------------------------------------------------- 4. 所有像 Windows 路径的串
print()
print("=" * 78)
print("4) 含反斜杠的路径常量（去重）")
print("=" * 78)
paths = re.findall(rb"[A-Za-z0-9_%\\.\- ]{0,40}(?:\\\\|/)[A-Za-z0-9_\\\\.\- ]{4,80}", data)
seen = []
s = set()
for p in paths:
    t = p.decode("ascii", "replace").strip()
    if len(t) < 6 or t in s:
        continue
    s.add(t)
    seen.append(t)
key = [t for t in seen if any(k in t.lower() for k in
       ("tencent", "wechat", "weixin", "radium", "applet", "xwechat", "roaming", "appdata", "%"))]
for t in key[:60]:
    print("   ", t)
print(f"   （含关键词的共 {len(key)} 条，全部路径 {len(seen)} 条）")
