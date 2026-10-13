#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
wxapkg_unpack.py —— 微信 PC 4.x「V1MMWX」加密 wxapkg 解密 + 解包

加密结构（逆向自微信 PC 客户端）：
    [0:6]        b'V1MMWX'
    [6:1030]     1024 字节，AES-CBC 加密的原始 wxapkg 头
    [1031:]      其余数据，单字节 XOR
    key = PBKDF2-HMAC-SHA1(password=appid, salt=SALT, iters=1000, dkLen=32)
    iv  = IV (16 字节 ASCII)
    xor_key = ord(appid[-2])

纯 Python 实现 AES，不依赖 pycryptodome。
"""

import os
import sys
import struct
import hashlib
import binascii

SALT = b"saltiest"
IV = b"the iv: 16 bytes"
XOR_FALLBACK = 0x66

# ------------------------------------------------------------------ AES

SBOX = bytes.fromhex(
    "637c777bf26b6fc53001672bfed7ab76"
    "ca82c97dfa5947f0add4a2af9ca472c0"
    "b7fd9326363ff7cc34a5e5f171d83115"
    "04c723c31896059a071280e2eb27b275"
    "09832c1a1b6e5aa0523bd6b329e32f84"
    "53d100ed20fcb15b6acbbe394a4c58cf"
    "d0efaafb434d338545f9027f503c9fa8"
    "51a3408f929d38f5bcb6da2110fff3d2"
    "cd0c13ec5f974417c4a77e3d645d1973"
    "60814fdc222a908846eeb814de5e0bdb"
    "e0323a0a4906245cc2d3ac629195e479"
    "e7c8376d8dd54ea96c56f4ea657aae08"
    "ba78252e1ca6b4c6e8dd741f4bbd8b8a"
    "703eb5664803f60e613557b986c11d9e"
    "e1f8981169d98e949b1e87e9ce5528df"
    "8ca1890dbfe6426841992d0fb054bb16"
)

INV_SBOX = bytearray(256)
for _i, _v in enumerate(SBOX):
    INV_SBOX[_v] = _i


def gmul(a: int, b: int) -> int:
    p = 0
    for _ in range(8):
        if b & 1:
            p ^= a
        hi = a & 0x80
        a = (a << 1) & 0xFF
        if hi:
            a ^= 0x1B
        b >>= 1
    return p


def xtime(a: int) -> int:
    a <<= 1
    return (a ^ 0x1B) & 0xFF if a & 0x100 else a


def key_expansion(key: bytes):
    nk = len(key) // 4
    nr = nk + 6
    w = [list(key[4 * i:4 * i + 4]) for i in range(nk)]
    rcon = 1
    for i in range(nk, 4 * (nr + 1)):
        temp = list(w[i - 1])
        if i % nk == 0:
            temp = temp[1:] + temp[:1]
            temp = [SBOX[b] for b in temp]
            temp[0] ^= rcon
            rcon = xtime(rcon)
        elif nk > 6 and i % nk == 4:
            temp = [SBOX[b] for b in temp]
        w.append([w[i - nk][j] ^ temp[j] for j in range(4)])
    return w, nr


def _ark(s, w, rnd):
    for c in range(4):
        word = w[rnd * 4 + c]
        for r in range(4):
            s[r][c] ^= word[r]


def _inv_shift_rows(s):
    for r in range(1, 4):
        s[r] = s[r][-r:] + s[r][:-r]


def _inv_sub_bytes(s):
    for r in range(4):
        for c in range(4):
            s[r][c] = INV_SBOX[s[r][c]]


def _inv_mix_columns(s):
    for c in range(4):
        a = [s[r][c] for r in range(4)]
        s[0][c] = gmul(a[0], 14) ^ gmul(a[1], 11) ^ gmul(a[2], 13) ^ gmul(a[3], 9)
        s[1][c] = gmul(a[0], 9) ^ gmul(a[1], 14) ^ gmul(a[2], 11) ^ gmul(a[3], 13)
        s[2][c] = gmul(a[0], 13) ^ gmul(a[1], 9) ^ gmul(a[2], 14) ^ gmul(a[3], 11)
        s[3][c] = gmul(a[0], 11) ^ gmul(a[1], 13) ^ gmul(a[2], 9) ^ gmul(a[3], 14)


def aes_decrypt_block(blk: bytes, w, nr: int) -> bytes:
    s = [[blk[r + 4 * c] for c in range(4)] for r in range(4)]
    _ark(s, w, nr)
    for rnd in range(nr - 1, 0, -1):
        _inv_shift_rows(s)
        _inv_sub_bytes(s)
        _ark(s, w, rnd)
        _inv_mix_columns(s)
    _inv_shift_rows(s)
    _inv_sub_bytes(s)
    _ark(s, w, 0)
    return bytes(s[r][c] for c in range(4) for r in range(4))


def aes_cbc_decrypt(data: bytes, key: bytes, iv: bytes) -> bytes:
    w, nr = key_expansion(key)
    out = bytearray()
    prev = iv
    for off in range(0, len(data), 16):
        blk = data[off:off + 16]
        plain = aes_decrypt_block(blk, w, nr)
        out.extend(bytes(a ^ b for a, b in zip(plain, prev)))
        prev = blk
    return bytes(out)


def selftest() -> bool:
    """FIPS-197 标准向量，确认 AES 实现正确。"""
    ok = True
    cases = [
        # (key, ciphertext, plaintext)
        ("000102030405060708090a0b0c0d0e0f",
         "69c4e0d86a7b0430d8cdb78070b4c55a",
         "00112233445566778899aabbccddeeff"),
        ("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
         "8ea2b7ca516745bfeafc49904b496089",
         "00112233445566778899aabbccddeeff"),
    ]
    for k, ct, pt in cases:
        got = aes_cbc_decrypt(bytes.fromhex(ct), bytes.fromhex(k), b"\x00" * 16)
        want = bytes.fromhex(pt)
        tag = "OK " if got == want else "FAIL"
        if got != want:
            ok = False
        print(f"  [{tag}] AES-{len(k)*4}bit  {got.hex()} vs {want.hex()}")
    return ok


# ------------------------------------------------------------------ 解密


def decrypt_v1mmwx(data: bytes, appid: str) -> bytes:
    if data[:6] != b"V1MMWX":
        return data
    key = hashlib.pbkdf2_hmac("sha1", appid.encode(), SALT, 1000, 32)
    head = aes_cbc_decrypt(data[6:1030], key, IV)
    xored = data[1031:]
    xor_key = ord(appid[-2]) if len(appid) >= 2 else XOR_FALLBACK
    tail = bytes(b ^ xor_key for b in xored)
    return head + tail


# ------------------------------------------------------------------ 解包


HEADER_LEN = 14


def parse_wxapkg(buf: bytes):
    """微信 PC 4.x 的 wxapkg 布局（与老版 17 字节头不同）：

        [0:14]       头部
        [5:9]        u32 BE  索引区长度 INDEX_LEN
        [9:13]       u32 BE  数据区长度 DATA_LEN
        [14:18]      u32 BE  文件数
        [18:14+INDEX_LEN]  条目表，每条 = u32 BE 名长 + 名 + u32 BE 偏移 + u32 BE 大小
        [14+INDEX_LEN:]    数据区

    校验式：14 + INDEX_LEN + DATA_LEN == len(buf)
    """
    if not buf or buf[0] != 0xBE:
        raise ValueError(f"不是合法 wxapkg，首字节 0x{buf[0]:02x} != 0xBE")

    index_len, data_len = struct.unpack(">II", buf[5:13])
    if HEADER_LEN + index_len + data_len != len(buf):
        raise ValueError(
            f"头部校验失败: 14+{index_len}+{data_len} = "
            f"{HEADER_LEN + index_len + data_len} != {len(buf)}")

    data_start = HEADER_LEN + index_len
    count = struct.unpack(">I", buf[HEADER_LEN:HEADER_LEN + 4])[0]

    files = []
    p = HEADER_LEN + 4
    for _ in range(count):
        (nlen,) = struct.unpack(">I", buf[p:p + 4])
        p += 4
        name = buf[p:p + nlen].decode("utf-8", "replace")
        p += nlen
        off, size = struct.unpack(">II", buf[p:p + 8])
        p += 8
        files.append((name, off, size))

    # 偏移量相对数据区起点；用首个文件的自校验确认
    if files:
        _, off0, size0 = files[0]
        abs0 = data_start + off0
        if not (0 <= abs0 and abs0 + size0 <= len(buf)):
            raise ValueError("条目偏移越界，格式假设有误")

    return files, data_start, buf


_BAD = set('<>:"|?*')


def safe_name(name: str) -> str:
    """清掉控制字符和 Windows 非法字符，保留目录层级。"""
    parts = []
    for seg in name.replace("\\", "/").split("/"):
        seg = "".join(c for c in seg if ord(c) >= 32 and c not in _BAD).strip(". ")
        if seg:
            parts.append(seg)
    return os.path.join(*parts) if parts else "unnamed"


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else None
    appid = sys.argv[2] if len(sys.argv) > 2 else "wx13b7520eb7351f4f"
    outdir = sys.argv[3] if len(sys.argv) > 3 else "unpacked"

    if not src:
        print("用法: wxapkg_unpack.py <__APP__.wxapkg> <appid> <输出目录>")
        return 1

    print("=== AES 自检 ===")
    if not selftest():
        print("AES 实现有误，终止")
        return 2

    with open(src, "rb") as f:
        raw = f.read()
    print(f"\n=== 解密 ===\n  原始 {len(raw)} 字节, 头 {raw[:6]!r}")
    plain = decrypt_v1mmwx(raw, appid)
    print(f"  解密后 {len(plain)} 字节, 首字节 0x{plain[0]:02x}")

    if plain[0] != 0xBE:
        print("  首字节不是 0xBE —— salt/iv/appid 不对，dump 前 64 字节供分析：")
        print("  ", binascii.hexlify(plain[:64]).decode())
        return 3

    files, data_start, buf = parse_wxapkg(plain)
    print(f"\n=== 解包：{len(files)} 个文件 ===")
    os.makedirs(outdir, exist_ok=True)
    total = 0
    for name, off, size in files:
        blob = buf[data_start + off: data_start + off + size]
        dst = os.path.join(outdir, safe_name(name))
        d = os.path.dirname(dst)
        if d:
            os.makedirs(d, exist_ok=True)
        with open(dst, "wb") as f:
            f.write(blob)
        total += size
    print(f"  写出 {total} 字节 -> {outdir}")

    # 按大小列出重点文件
    print("\n=== 主要文件 ===")
    for name, off, size in sorted(files, key=lambda x: -x[2])[:30]:
        print(f"  {size:>8}  {name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
