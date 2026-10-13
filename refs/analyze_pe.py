#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PE 静态识别：判断 采集器.exe 是用什么打包的。"""
import struct, os, re, collections
import os
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

P = os.environ.get("COLLECTOR_EXE", os.path.join(HERE, "采集器.exe"))
data = open(P, "rb").read()
print(f"文件 {len(data):,} 字节\n")

# ---- DOS / PE 头 ----
pe_off = struct.unpack_from("<I", data, 0x3C)[0]
assert data[pe_off:pe_off + 4] == b"PE\0\0", "不是有效 PE"
machine, nsec, _, _, _, opt_size, chars = struct.unpack_from("<HHIIIHH", data, pe_off + 4)
magic = struct.unpack_from("<H", data, pe_off + 24)[0]
print(f"PE 偏移       0x{pe_off:x}")
print(f"machine       0x{machine:04x}  ({'x64' if machine==0x8664 else 'x86' if machine==0x14c else '?'})")
print(f"节数          {nsec}")
print(f"可选头 magic  0x{magic:04x}  ({'PE32+' if magic==0x20b else 'PE32'})")

opt = pe_off + 24
is64 = magic == 0x20b
ddoff = opt + (112 if is64 else 96)
num_dd = struct.unpack_from("<I", data, opt + (108 if is64 else 92))[0]
print(f"数据目录项    {num_dd}")

names = {0: "Export", 1: "Import", 2: "Resource", 5: "Reloc", 9: "TLS", 12: "IAT", 14: "COM/.NET"}
print("\n--- 数据目录 ---")
for i in range(min(num_dd, 16)):
    rva, sz = struct.unpack_from("<II", data, ddoff + i * 8)
    if rva or sz:
        tag = names.get(i, "")
        print(f"  [{i:2d}] {tag:<10} RVA=0x{rva:08x} size={sz:,}")

# ---- 节表 ----
sec_off = opt + opt_size
print("\n--- 节表 ---")
secs = []
for i in range(nsec):
    o = sec_off + i * 40
    name = data[o:o + 8].rstrip(b"\0").decode("latin1")
    vsize, vaddr, rsize, rawptr = struct.unpack_from("<IIII", data, o + 8)
    secs.append((name, vaddr, vsize, rawptr, rsize))
    ent = (rsize / vsize) if vsize else 0
    flag = "  <-- 高熵，可能加壳/压缩" if ent > 0.9 and rsize > 0 else ""
    print(f"  {name:<9} VA=0x{vaddr:08x} VSize={vsize:>10,} RawSize={rsize:>10,} 比={ent:.2f}{flag}")

file_end = max((rp + rs) for _, _, _, rp, rs in secs if rp)
print(f"\n节区数据结束于 {file_end:,}")
overlay = len(data) - file_end
print(f"附加数据(overlay) {overlay:,} 字节")
if overlay > 1024:
    head = data[file_end:file_end + 64]
    print(f"  overlay 头部: {head}")
    print(f"  overlay ASCII: {''.join(chr(c) if 32<=c<127 else '.' for c in head)}")

# ---- 打包器指纹 ----
print("\n--- 打包器指纹 ---")
fp = {
    ".NET / CLR":       [b"mscoree.dll", b"_CorExeMain", b"mscorlib"],
    "PyInstaller":      [b"PYZ-00.pyz", b"_MEIPASS", b"pyi-", b"python3"],
    "Electron":         [b"app.asar", b"electron", b"chrome_100_percent.pak", b"v8_context_snapshot"],
    "Go":               [b"Go build ID", b"gopclntab", b"runtime.main"],
    "NSIS/Inno":        [b"Nullsoft", b"Inno Setup"],
    "UPX":              [b"UPX0", b"UPX1", b"UPX!"],
    "Nuitka":           [b"Nuitka", b"__nuitka"],
    "node/nodejs":      [b"NODE_SEA_BLOB", b"node_modules", b"node.exe"],
    "Rust/Tauri":       [b"tauri", b"rustc", b"cargo"],
    "Flutter":          [b"libflutter.so", b"flutter_assets"],
    "AutoIt":           [b"AU3!", b"AutoIt"],
}
for name, sigs in fp.items():
    hits = [s.decode("latin1") for s in sigs if s in data]
    if hits:
        print(f"  [命中] {name:<16} {hits}")

# ---- overlay 里的字符串（PyInstaller 常见）----
if overlay > 1024:
    print("\n--- overlay 内可打印串（前 40 条，去重）---")
    ov = data[file_end:]
    strs = re.findall(rb"[\x20-\x7e]{8,}", ov)
    seen = set()
    n = 0
    for s in strs:
        t = s.decode("ascii", "replace")
        if t in seen:
            continue
        seen.add(t)
        print("   ", t[:120])
        n += 1
        if n >= 40:
            break
