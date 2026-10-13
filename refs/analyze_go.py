#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 Go 二进制里掏 build info、函数名、关键字符串。"""
import re, os, collections
import os
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

P = os.environ.get("COLLECTOR_EXE", os.path.join(HERE, "采集器.exe"))
data = open(P, "rb").read()
OUT = HERE

# ---------------------------------------------------------------- 1. Go build info
print("=" * 78)
print("1) Go build info")
print("=" * 78)
magic = b"\xff Go buildinf:"
i = data.find(magic)
if i >= 0:
    print(f"   魔数位置 0x{i:x}")
    blob = data[i:i + 512]
    txt = "".join(chr(c) if 32 <= c < 127 else "\n" for c in blob)
    lines = [l.strip() for l in txt.split("\n") if len(l.strip()) > 3]
    for l in lines[:40]:
        print("   ", l)
else:
    print("   未找到 buildinfo 魔数")

# Go 1.18+ 还会在 .rdata 里存 module 信息文本
print("\n   --- 疑似 module / 依赖路径 ---")
mods = set(re.findall(rb"(?:github\.com|golang\.org|gopkg\.in|google\.golang\.org)/[A-Za-z0-9_./\-]{4,60}", data))
for m in sorted(mods)[:60]:
    print("   ", m.decode())

# ---------------------------------------------------------------- 2. 全部 ASCII 串
print()
print("=" * 78)
print("2) 字符串总量")
raw = re.findall(rb"[\x20-\x7e\t]{4,}", data)
strs = [s.decode("ascii", "replace") for s in raw]
uniq = []
seen = set()
for s in strs:
    if s not in seen:
        seen.add(s)
        uniq.append(s)
print(f"   原始 {len(strs):,} 条，去重后 {len(uniq):,} 条")

# ---------------------------------------------------------------- 3. 关键词命中
print()
print("=" * 78)
print("3) 关键词命中")
print("=" * 78)

GROUPS = {
    "微信数据目录": ["xwechat", "WeChat Files", "WeChat\\", "Tencent\\WeChat", "Applet", "radium", "WeChatAppEx"],
    "小程序存储":   ["mmkv", "MMKV", "applet.db", "usrmmkvstorage", "wxapkg", "localstorage", "Local Storage"],
    "身份字段":     ["openid", "unionid", "unid", "token", "session", "studentNo", "unifyId", "checkBinding"],
    "错误码":       ["E8301", "E8302", "E8303", "E8101", "E8102", "E8103", "E8204", "E8205", "E8002", "E8990"],
    "网络":         ["https://", "http://", "api", "Bearer", "Authorization"],
    "加密库":       ["AES", "aes", "PBKDF2", "pbkdf2", "sha1", "sha256", "saltiest", "V1MMWX"],
    "SQLite":       ["sqlite", "SQLite", "SELECT ", "SELECT", "FROM ", "PRAGMA"],
    "目标系统":     ["sport.cqupt", "cqupt", "new_wxapp", "sportRecord", "wxUnifyId"],
}

for g, kws in GROUPS.items():
    print(f"\n--- {g} ---")
    hit = collections.OrderedDict()
    for s in uniq:
        for k in kws:
            if k in s:
                hit.setdefault(s, k)
                break
    if not hit:
        print("   （无命中）")
    for s, k in list(hit.items())[:35]:
        print(f"   [{k:<14}] {s[:130]}")
    if len(hit) > 35:
        print(f"   ... 共 {len(hit)} 条")

# ---------------------------------------------------------------- 4. Go 函数名
print()
print("=" * 78)
print("4) Go 函数名 / 符号（挑应用自身的，去掉标准库）")
print("=" * 78)
fn = re.findall(rb"[A-Za-z0-9_]+(?:[./][A-Za-z0-9_\-]+){1,8}\.[A-Za-z0-9_]{3,}", data)
names = sorted({f.decode("ascii", "replace") for f in fn})
app = [n for n in names if not n.startswith(("runtime.", "internal/", "syscall.", "reflect.",
                                            "sync.", "fmt.", "os.", "strconv.", "unicode/",
                                            "crypto/", "encoding/", "net/", "time.", "context.",
                                            "math.", "sort.", "strings.", "bytes.", "io.", "bufio."))]
print(f"   符号总数 {len(names):,}，非标准库 {len(app):,}")
for n in app[:150]:
    print("   ", n)

with open(os.path.join(OUT, "collector_strings.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(uniq))
print(f"\n[+] 全部去重字符串 -> refs/collector_strings.txt")
