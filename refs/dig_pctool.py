#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""深挖 pctool：自身符号、关键逻辑周边字符串、内嵌前端资源。"""
import re, os
import os
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

P = os.environ.get("COLLECTOR_EXE", os.path.join(HERE, "采集器.exe"))
data = open(P, "rb").read()

def window(keyword: bytes, before=0, after=600, limit=6):
    """Go 的字符串是连续存放的，取命中位置的原始窗口。"""
    out = []
    i = 0
    while True:
        i = data.find(keyword, i)
        if i < 0 or len(out) >= limit:
            break
        blob = data[max(0, i - before):i + after]
        txt = "".join(chr(c) if 32 <= c < 127 else "\u00b7" for c in blob)
        out.append((i, txt))
        i += 1
    return out

print("=" * 78)
print("1) 程序自身的符号（pctool/*）")
print("=" * 78)
syms = sorted({m.decode() for m in re.findall(rb"pctool/[A-Za-z0-9_./\-]{2,80}", data)})
for s in syms:
    print("   ", s)
print(f"   共 {len(syms)} 个")

print()
print("=" * 78)
print("2) 关键函数周边的字符串（Go 连续字符串池）")
print("=" * 78)
for kw in [b"radiumRoot", b"aesKey", b"json:\"openid\"", b"E8301", b"E8302", b"E8303",
           b"E8204", b"E8205", b"E8002"]:
    print(f"\n--- {kw.decode(errors='replace')} ---")
    for off, txt in window(kw, 0, 420, 4):
        print(f"   @0x{off:x}: {txt}")

print()
print("=" * 78)
print("3) 微信路径 / 进程名相关")
print("=" * 78)
for kw in [b"WeChatAppEx", b"xwechat_files", b"\\radium", b"Applet", b"Tencent"]:
    print(f"\n--- {kw.decode(errors='replace')} ---")
    for off, txt in window(kw, 0, 360, 4):
        print(f"   @0x{off:x}: {txt}")

print()
print("=" * 78)
print("4) 内嵌前端资源（Wails 会把 HTML/JS 打进二进制）")
print("=" * 78)
for kw in [b"<!DOCTYPE", b"<html", b"fetch(", b"/api/", b"openid"]:
    hits = [m.start() for m in re.finditer(re.escape(kw), data)]
    print(f"   {kw.decode(errors='replace'):<12} 命中 {len(hits)} 处")
    for o in hits[:3]:
        blob = data[o:o + 300]
        txt = "".join(chr(c) if 32 <= c < 127 else "\u00b7" for c in blob)
        print(f"      @0x{o:x}: {txt[:240]}")
