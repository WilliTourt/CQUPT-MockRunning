#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
重邮智慧体育 openid 提取器
==========================

openid 是你在「重邮智慧体育」小程序里的身份编号。
有了它就能一直换取登录凭证，所以只需要拿一次。

原理
----
电脑版微信 4.x 把小程序跑在 Chromium 容器里（WeChatAppEx），
小程序调 /wxUnifyId/checkBinding 的响应体是明文 JSON，直接缓存在：

    %APPDATA%\\Tencent\\xwechat\\radium\\web\\profiles\\webview_<hash>\\Cache\\Cache_Data\\

里面就有 openid。不用解密、不用抓包、不联网。

使用
----
1. 电脑装 Python 3：https://www.python.org/downloads/  （安装时勾上 Add to PATH）
2. 电脑微信打开一次「重邮智慧体育」，完成登录绑定
3. 把本文件存成 get_openid.py
4. 双击运行，或命令行执行：python get_openid.py
5. 输出里的 openid 填进 App 的【打卡】页

注意
----
* 手机拿不到：它存在微信的私有数据里，普通 App 无权限读取（除非手机已 root）。
* 不用退出微信：读的是 HTTP 缓存，和数据库文件不同，不会锁。
"""

import os
import re
import sys

WECHAT_ROOTS = [
    os.path.expandvars(r"%APPDATA%\Tencent\xwechat"),
    os.path.expandvars(r"%APPDATA%\Tencent\WeChat"),
]

OPENID_JSON = re.compile(rb'"openid"\s*:\s*"([A-Za-z0-9_\-]{7,64})"')
OPENID_QUERY = re.compile(rb"[?&]openid=([A-Za-z0-9_\-]{7,64})")
USER_JSON = re.compile(
    rb'\{"id":\d+[^{}]*?"unifyId"\s*:\s*"([^"]*)"[^{}]*?"studentNo"\s*:\s*"([^"]*)"')


def _win_read_shared(path):
    """用 CreateFileW + 完整共享标志读取文件。

    微信运行时会把 HTTP 缓存文件（index/data_N，openid 就在这里面）独占打开，
    普通 open() 会 PermissionError。这里请求 READ|WRITE|DELETE 共享，可绕过。
    """
    import ctypes
    from ctypes import wintypes

    GENERIC_READ = 0x80000000
    FILE_SHARE_READ, FILE_SHARE_WRITE, FILE_SHARE_DELETE = 1, 2, 4
    OPEN_EXISTING = 3
    FILE_ATTRIBUTE_NORMAL = 0x80
    INVALID = wintypes.HANDLE(-1).value

    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    k32.CreateFileW.restype = wintypes.HANDLE
    k32.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                                wintypes.LPVOID, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
    k32.ReadFile.argtypes = [wintypes.HANDLE, wintypes.LPVOID, wintypes.DWORD,
                             ctypes.POINTER(wintypes.DWORD), wintypes.LPVOID]
    k32.CloseHandle.argtypes = [wintypes.HANDLE]

    h = k32.CreateFileW(path, GENERIC_READ,
                        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                        None, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, None)
    if h == INVALID:
        return None
    chunks = []
    buf = ctypes.create_string_buffer(1 << 20)
    read = wintypes.DWORD(0)
    try:
        while True:
            if not k32.ReadFile(h, buf, len(buf), ctypes.byref(read), None):
                break
            if read.value == 0:
                break
            chunks.append(buf.raw[:read.value])
    finally:
        k32.CloseHandle(h)
    return b"".join(chunks)


def read_file(path):
    """先尝试普通读取，失败（被微信锁住）再用共享模式读取。"""
    try:
        with open(path, "rb") as f:
            return f.read()
    except OSError:
        pass
    if os.name == "nt":
        return _win_read_shared(path)
    return None


def cache_dirs():
    for root in WECHAT_ROOTS:
        profiles = os.path.join(root, "radium", "web", "profiles")
        if not os.path.isdir(profiles):
            continue
        for name in os.listdir(profiles):
            d = os.path.join(profiles, name, "Cache", "Cache_Data")
            if os.path.isdir(d):
                yield d


def scan():
    found = {}
    for d in cache_dirs():
        for fn in os.listdir(d):
            p = os.path.join(d, fn)
            if not os.path.isfile(p):
                continue
            try:
                if os.path.getsize(p) > 64 * 1024 * 1024:
                    continue
            except OSError:
                pass
            b = read_file(p)
            if not b or b"openid" not in b:
                continue

            ids = {m.group(1).decode() for m in OPENID_JSON.finditer(b)}
            ids |= {m.group(1).decode() for m in OPENID_QUERY.finditer(b) if m.group(1)}
            users = USER_JSON.findall(b)

            for oid in ids:
                rec = found.setdefault(oid, {
                    "openid": oid, "unifyId": None, "studentNo": None,
                    "source": "%s/%s" % (os.path.basename(os.path.dirname(os.path.dirname(d))), fn),
                })
                for uid, sno in users:
                    rec["unifyId"] = rec["unifyId"] or uid.decode("utf-8", "replace")
                    rec["studentNo"] = rec["studentNo"] or sno.decode("utf-8", "replace")
    return list(found.values())


def main():
    print("=" * 56)
    print("  重邮智慧体育 · openid 提取器")
    print("=" * 56)
    print()

    if not any(os.path.isdir(r) for r in WECHAT_ROOTS):
        print("[X] 没找到电脑版微信的数据目录。")
        print("    先装官方 PC 微信并登录，用电脑打开一次「重邮智慧体育」完成绑定。")
        return 2

    if not list(cache_dirs()):
        print("[X] 没找到小程序缓存目录。")
        print("    在电脑微信里打开一次「重邮智慧体育」小程序，再重试。")
        return 3

    hits = scan()
    if not hits:
        print("[X] 缓存里没有 openid。")
        print("    说明这个微信号还没在电脑上打开过该小程序。")
        print("    在电脑微信里打开一次并完成登录绑定，然后重试。")
        return 4

    print("找到 %d 个身份：" % len(hits))
    print()
    for i, h in enumerate(hits, 1):
        print("  [%d] openid" % i)
        print("      %s" % h["openid"])
        print("      尾号 %s" % ("****" + h["openid"][-4:]))
        if h.get("unifyId"):
            print("      统一认证码 %s" % h["unifyId"])
        if h.get("studentNo"):
            print("      学号 %s" % h["studentNo"])
        print()

    if len(hits) == 1:
        print("-" * 56)
        print("把这个 openid 填进 App 的【打卡】页即可：")
        print()
        print("  %s" % hits[0]["openid"])
        print()
        print("-" * 56)

    return 0


if __name__ == "__main__":
    code = 0
    try:
        code = main()
    except Exception as e:
        print("[X] 出错了：%s: %s" % (type(e).__name__, e))
        code = 1
    print()
    try:
        input("按回车键关闭…")
    except EOFError:
        pass
    sys.exit(code)
