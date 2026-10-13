#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import re, os
import os
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

P = os.environ.get("COLLECTOR_EXE", os.path.join(HERE, "采集器.exe"))
OUT = HERE
data = open(P, "rb").read()
rep = []
def R(*a): rep.append(" ".join(str(x) for x in a))

R("=" * 78)
R("1) UTF-16LE 路径搜索")
R("=" * 78)
for k in ["xwechat", "Tencent", "radium", "profiles", "Applet", "wechat", "WeChat",
          "AppData", "Roaming", "users", "webview", "openid", "token", "APP_DATA"]:
    u16 = k.encode("utf-16-le")
    hits = [m.start() for m in re.finditer(re.escape(u16), data)]
    R(f"   {k:<12} UTF-16 {len(hits):>4} 处   {[hex(h) for h in hits[:6]]}")
    for h in hits[:3]:
        blob = data[max(0, h - 40):h + 200]
        t = blob.decode("utf-16-le", "replace")
        t = "".join(c if 32 <= ord(c) < 0xFFFF else "." for c in t)
        R("        ", t[:120])

R("")
R("=" * 78)
R("2) 注册表 / 环境变量相关的字面量")
R("=" * 78)
for k in ["Software\\Tencent", "Tencent\\WeChat", "InstallPath", "FileSavePath", "RegQuery",
          "SOFTWARE\\", "HKEY_", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "Program Files"]:
    hits = [m.start() for m in re.finditer(re.escape(k.encode()), data)]
    R(f"   {k:<20} {len(hits):>4} 处")
    for h in hits[:2]:
        blob = data[max(0, h - 60):h + 200]
        t = "".join(chr(c) if 32 <= c < 127 else "." for c in blob)
        R("        ", t[:170])

R("")
R("=" * 78)
R("3) 前端（从 0x5cd8a0 起，正确的 index.html）")
R("=" * 78)
start = 0x5cd8a0 - 200
end = min(len(data), 0x5d6000)
blob = data[start:end]
txt = blob.decode("utf-8", "replace")
txt = "".join(c if (c in "\n\r\t" or 32 <= ord(c) < 0xFFFF) else "." for c in txt)
with open(os.path.join(OUT, "index_html.txt"), "w", encoding="utf-8") as f:
    f.write(txt)
R(f"   导出 0x{start:x}..0x{end:x} ({len(txt):,} 字符) -> index_html.txt")
# 只保留看起来像 HTML/JS 的行
lines = [l for l in txt.split("\n") if re.search(r"(<|function|const |let |var |=>|window\.|await |return )", l)]
R(f"   其中像代码的行 {len(lines)} 条：")
for l in lines[:120]:
    R("     ", l.strip()[:200])

R("")
R("=" * 78)
R("4) 所有含 'openid' / 'Openid' 的上下文")
R("=" * 78)
for m in list(re.finditer(rb"[Oo]penid", data))[:8]:
    h = m.start()
    blob = data[max(0, h - 300):h + 300]
    t = "".join(chr(c) if 32 <= c < 127 else "." for c in blob)
    R(f"   @0x{h:x}: {t}")

with open(os.path.join(OUT, "dig_report3.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(rep))
print("-> refs/dig_report3.txt")
