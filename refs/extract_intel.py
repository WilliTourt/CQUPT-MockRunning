#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从解包后的小程序 JS 里抽取接口、加密、定位相关情报。"""
import os, re, json, collections
import os
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

ROOT = os.path.join(HERE, "unpacked")

def load_all():
    blobs = {}
    for dirpath, _, names in os.walk(ROOT):
        for n in names:
            if n.endswith(".js"):
                p = os.path.join(dirpath, n)
                with open(p, "r", encoding="utf-8", errors="replace") as f:
                    blobs[os.path.relpath(p, ROOT)] = f.read()
    return blobs

blobs = load_all()
total = sum(len(v) for v in blobs.values())
print(f"载入 {len(blobs)} 个 js, 共 {total} 字符\n")

# ---------- 1. 接口路径 ----------
pat_path = re.compile(r'"((?:/)?(?:new_wxapp/)?(?:wx|sport|run|user|login|face|record|api)[A-Za-z0-9_/]*)"')
paths = collections.Counter()
for f, s in blobs.items():
    for m in pat_path.finditer(s):
        p = m.group(1)
        if len(p) > 3 and "/" in p or p.startswith("wx"):
            paths[p] += 1
print("=" * 70)
print("1) 疑似接口路径")
for p, c in sorted(paths.items()):
    if len(p) > 4:
        print(f"   {c:>3}x  {p}")

# ---------- 2. wx.request / socket 调用点上下文 ----------
print()
print("=" * 70)
print("2) 网络调用上下文")
for kw in ("wx.request", "wx.connectSocket", "sendSocketMessage", "onSocketMessage", "wx.uploadFile"):
    hits = 0
    for f, s in blobs.items():
        for m in re.finditer(re.escape(kw), s):
            hits += 1
            if hits <= 6:
                a = max(0, m.start() - 120); b = m.start() + 320
                print(f"   [{f}] ...{s[a:b]}...".replace("\n", " "))
                print()
    print(f"   >>> {kw} 共 {hits} 处\n")

# ---------- 3. 加密 / 签名 ----------
print("=" * 70)
print("3) 加密 / 签名关键词")
for kw in ("md5", "MD5", "sha1", "SHA", "AES", "aes", "RSA", "rsa", "publicKey",
           "encrypt", "decrypt", "sign", "Signature", "hmac", "base64", "CryptoJS"):
    n = sum(s.count(kw) for s in blobs.values())
    if n:
        print(f"   {n:>4}x  {kw}")

# ---------- 4. 定位 / 跑步相关 ----------
print()
print("=" * 70)
print("4) 定位 / 跑步关键词")
for kw in ("startLocationUpdate", "onLocationChange", "getLocation", "latitude", "longitude",
           "pointCount", "notInPlayCount", "pace", "speed", "faceImg", "scanFace",
           "startRun", "endRun", "stopRun", "sportRecordNo"):
    n = sum(s.count(kw) for s in blobs.values())
    if n:
        print(f"   {n:>4}x  {kw}")

# ---------- 5. 把 socket 协议那一段单独抓出来 ----------
print()
print("=" * 70)
print("5) WebSocket 协议片段")
for f, s in blobs.items():
    for m in re.finditer(r"sendSocketMessage|connectSocket|socketUrl", s):
        a = max(0, m.start() - 400); b = m.start() + 700
        print(f"--- {f} @{m.start()} ---")
        print(s[a:b])
        print()
        break
