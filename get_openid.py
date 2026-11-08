#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
重邮智慧体育 openid 提取器 (Linux / Windows / macOS 跨平台通用)
=============================================================

openid 是你在「重邮智慧体育」小程序里的身份编号。
有了它就能一直换取登录凭证，所以只需要拿一次。

原理
----
电脑版微信 4.x / 3.x 把小程序跑在 Chromium 容器里（WeChatAppEx），
小程序调 /wxUnifyId/checkBinding 等接口的响应体是明文 JSON，直接缓存在本地：

  Linux:   ~/.xwechat/radium/web/profiles/webview_<hash>/Cache/Cache_Data/
  Windows: %APPDATA%\\Tencent\\xwechat\\radium\\web\\profiles\\webview_<hash>\\Cache\\Cache_Data\\

里面就有 openid。不用解密、不用抓包、不联网。

注意（Linux 常见坑）add from gufandf
------------------
Linux 版微信允许把「数据目录」改到任意位置。改过之后 ~/.xwechat
只是个空壳，真实缓存在类似下面的地方：

  ~/Documents/WeChat_Data/home/.xwechat/radium/web/profiles/...

若只看 ~/.xwechat 就会一直报「缓存里没有 openid」。本脚本会在
已知路径没命中时自动扫描家目录（约 0.1 秒），把这类重定向目录也找出来。

使用
----
1. 电脑安装微信并登录
2. 电脑微信打开一次「重邮智慧体育」小程序，完成登录绑定
3. 命令行执行：python3 get_openid.py
4. 将提取到的 openid 填入打卡脚本或 App 即可

可选参数：
  python3 get_openid.py <目录>   只查指定目录（不触发自动扫描）
  python3 get_openid.py --all    强制执行自动扫描
  python3 get_openid.py --verbose  打印出每个被检查的缓存目录
"""

import os
import re
import sys


def get_default_wechat_roots():
    """获取不同系统下微信可能的数据根目录（支持 Linux / Windows / macOS / Wine）"""
    candidates = []

    # 1. 允许命令行传入指定路径
    if len(sys.argv) > 1 and os.path.exists(sys.argv[1]):
        candidates.append(os.path.abspath(sys.argv[1]))

    # 2. Windows 平台路径
    if os.name == "nt":
        appdata = os.environ.get("APPDATA", "")
        if appdata:
            candidates.extend([
                os.path.join(appdata, "Tencent", "xwechat"),
                os.path.join(appdata, "Tencent", "WeChat"),
            ])
        local_appdata = os.environ.get("LOCALAPPDATA", "")
        if local_appdata:
            candidates.extend([
                os.path.join(local_appdata, "Tencent", "xwechat"),
                os.path.join(local_appdata, "Tencent", "WeChat"),
            ])

    # 3. Linux / Unix 平台路径 (原生 Linux 微信 4.x 默认在 ~/.xwechat)
    home = os.path.expanduser("~")
    candidates.extend([
        os.path.join(home, ".xwechat"),
        os.path.join(home, ".local", "share", "xwechat"),
        os.path.join(home, ".var", "app", "com.tencent.WeChat", "data", "xwechat"),
        os.path.join(home, ".config", "xwechat"),
    ])

    # Wine / Proton 环境 (在 Linux 上使用 Wine 版微信)
    wine_prefix = os.environ.get("WINEPREFIX", os.path.join(home, ".wine"))
    username = os.environ.get("USER", "")
    if username and os.path.isdir(wine_prefix):
        candidates.extend([
            os.path.join(wine_prefix, "drive_c", "users", username, "AppData", "Roaming", "Tencent", "xwechat"),
            os.path.join(wine_prefix, "drive_c", "users", username, "AppData", "Roaming", "Tencent", "WeChat"),
        ])

    # 4. macOS 平台路径
    candidates.extend([
        os.path.join(home, "Library", "Containers", "com.tencent.xinWeChat", "Data", "Library", "Application Support", "com.tencent.xinWeChat"),
        os.path.join(home, "Library", "Application Support", "com.tencent.xinWeChat"),
    ])

    # 去重并筛选存在的目录
    seen = set()
    existing = []
    for p in candidates:
        norm = os.path.normpath(p)
        if norm not in seen and os.path.isdir(norm):
            seen.add(norm)
            existing.append(norm)

    # 若未找到现有目录，返回平台代表性路径用于提示用户
    if not existing:
        if os.name == "nt":
            appdata = os.environ.get("APPDATA", r"C:\Users\Username\AppData\Roaming")
            return [os.path.join(appdata, "Tencent", "xwechat")]
        else:
            return [os.path.join(home, ".xwechat")]

    return existing


WECHAT_ROOTS = get_default_wechat_roots()

OPENID_JSON = re.compile(rb'"openid"\s*:\s*"([A-Za-z0-9_\-]{7,64})"')
OPENID_QUERY = re.compile(rb"[?&]openid=([A-Za-z0-9_\-]{7,64})")
STUDENT_NO = re.compile(rb'"studentNo"\s*:\s*"([^"]+)"')
UNIFY_ID = re.compile(rb'"unifyId"\s*:\s*"([^"]+)"')
STUDENT_NAME = re.compile(rb'"(?:studentName|username|createName)"\s*:\s*"([^"]+)"')
DEPT_NAME = re.compile(rb'"deptName"\s*:\s*"([^"]+)"')


def _win_read_shared(path):
    """用 CreateFileW + 完整共享标志读取文件 (Windows 专用，防微信独占锁)。"""
    if os.name != "nt":
        return None
    try:
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
    except Exception:
        return None


def read_file(path):
    """尝试读取文件内容。先普通读取，失败且在 Windows 时尝试共享模式。"""
    try:
        with open(path, "rb") as f:
            return f.read()
    except OSError:
        pass
    if os.name == "nt":
        return _win_read_shared(path)
    return None


def cache_dirs(roots=None):
    if roots is None:
        roots = WECHAT_ROOTS

    found = []
    for root in roots:
        # 支持直接指定 Cache 或 Cache_Data
        if os.path.basename(root) == "Cache_Data" and os.path.isdir(root):
            found.append(root)
            continue
        if os.path.basename(root) == "Cache" and os.path.isdir(root):
            cd = os.path.join(root, "Cache_Data")
            found.append(cd if os.path.isdir(cd) else root)
            continue

        # 两种布局都要试：4.x 是 <root>/radium/web/profiles，
        # 也有环境是 <root>/web/profiles。以前只取其中一个，
        # 把根目录直接指到 ~/.xwechat 时就可能漏掉缓存。
        for profiles in (
            os.path.join(root, "radium", "web", "profiles"),
            os.path.join(root, "web", "profiles"),
        ):
            if not os.path.isdir(profiles):
                continue
            for name in os.listdir(profiles):
                p = os.path.join(profiles, name)
                if not os.path.isdir(p):
                    continue
                d = os.path.join(p, "Cache", "Cache_Data")
                if os.path.isdir(d):
                    found.append(d)
                else:
                    d2 = os.path.join(p, "Cache")
                    if os.path.isdir(d2):
                        found.append(d2)
    return found


# 自动发现时跳过的目录：体积大且与微信无关
_SKIP_NAMES = {
    "node_modules", ".git", ".svn", ".hg", "site-packages", "__pycache__",
    "Steam", "steamapps", "snap", ".npm", ".cargo", ".rustup", ".gradle",
    ".m2", ".conda", ".nvm", ".pyenv", ".cache", ".Trash", ".local/share/Steam",
}


def _iter_cache_profiles(profiles_dir):
    """从 <...>/radium/web/profiles 里取出各 profile 的 Cache_Data。"""
    out = []
    try:
        names = os.listdir(profiles_dir)
    except OSError:
        return out
    for name in names:
        p = os.path.join(profiles_dir, name)
        if not os.path.isdir(p):
            continue
        d = os.path.join(p, "Cache", "Cache_Data")
        if os.path.isdir(d):
            out.append(d)
            continue
        d2 = os.path.join(p, "Cache")
        if os.path.isdir(d2):
            out.append(d2)
    return out


def discover_cache_dirs(search_roots=None, max_depth=6):
    """兜底自动发现。

    微信 Linux 版允许把「数据目录」重定向到任意位置（例如
    ~/Documents/WeChat_Data/home，此时真实数据在
    <数据目录>/.xwechat/radium/web/profiles）。这种路径无法反查，
    只能扫。以「存在 radium/web/profiles（或 web/profiles）」为信号，
    命中后不再深入该分支，避免把整个家目录走一遍。
    """
    if search_roots is None:
        search_roots = [os.path.expanduser("~")]

    found = []
    seen_profiles = set()

    def walk(base, depth):
        if depth > max_depth:
            return
        try:
            entries = list(os.scandir(base))
        except OSError:
            return
        for e in entries:
            if not e.is_dir(follow_symlinks=False):
                continue
            name = e.name
            if name in _SKIP_NAMES:
                continue
            path = e.path
            # 命中微信 web 容器目录：<x>/radium/web/profiles 或 <x>/web/profiles
            if name == "web" and os.path.isdir(os.path.join(path, "profiles")):
                prof = os.path.join(path, "profiles")
                if prof in seen_profiles:
                    continue
                seen_profiles.add(prof)
                found.extend(_iter_cache_profiles(prof))
                continue  # 已经是微信容器，不再深入
            # 直接命中 profiles 目录
            if name == "profiles" and os.path.basename(base) == "web":
                if path not in seen_profiles:
                    seen_profiles.add(path)
                    found.extend(_iter_cache_profiles(path))
                continue
            walk(path, depth + 1)

    for r in search_roots:
        if os.path.isdir(r):
            walk(r, 0)

    return found


def scan(dirs=None):
    cqupt_found = {}
    other_found = {}

    if dirs is None:
        dirs = cache_dirs()

    for d in dirs:
        if not os.path.isdir(d):
            continue

        prof_meta = {
            "studentNo": None,
            "unifyId": None,
            "studentName": None,
            "deptName": None,
        }

        # 扫描当前 profile 下所有缓存文件
        files = []
        try:
            files = [os.path.join(d, fn) for fn in os.listdir(d)]
        except OSError:
            continue

        # 第一遍：收集重邮智慧体育在当前 profile 的身份元数据（姓名、学号等）
        for p in files:
            if not os.path.isfile(p):
                continue
            try:
                if os.path.getsize(p) > 64 * 1024 * 1024:
                    continue
            except OSError:
                continue

            b = read_file(p)
            if not b:
                continue

            if b"cqupt" in b or b"checkBinding" in b or b"sportRecord" in b:
                for sno in STUDENT_NO.findall(b):
                    prof_meta["studentNo"] = prof_meta["studentNo"] or sno.decode("utf-8", "replace")
                for uid in UNIFY_ID.findall(b):
                    prof_meta["unifyId"] = prof_meta["unifyId"] or uid.decode("utf-8", "replace")
                for sname in STUDENT_NAME.findall(b):
                    prof_meta["studentName"] = prof_meta["studentName"] or sname.decode("utf-8", "replace")
                for dname in DEPT_NAME.findall(b):
                    prof_meta["deptName"] = prof_meta["deptName"] or dname.decode("utf-8", "replace")

        # 第二遍：提取 openid 并关联信息
        for p in files:
            if not os.path.isfile(p):
                continue
            try:
                if os.path.getsize(p) > 64 * 1024 * 1024:
                    continue
            except OSError:
                continue

            b = read_file(p)
            if not b or b"openid" not in b:
                continue

            ids = {m.group(1).decode() for m in OPENID_JSON.finditer(b)}
            ids |= {m.group(1).decode() for m in OPENID_QUERY.finditer(b) if m.group(1)}

            is_cqupt = (b"cqupt" in b or b"checkBinding" in b or b"sportRecord" in b)
            fn = os.path.basename(p)
            prof_name = os.path.basename(os.path.dirname(os.path.dirname(p)))
            source = "%s/%s" % (prof_name, fn)

            for oid in ids:
                if is_cqupt:
                    rec = cqupt_found.setdefault(oid, {
                        "openid": oid,
                        "unifyId": prof_meta["unifyId"],
                        "studentNo": prof_meta["studentNo"],
                        "studentName": prof_meta["studentName"],
                        "deptName": prof_meta["deptName"],
                        "source": source,
                        "is_cqupt": True,
                    })
                    # 如果本文件内有独立匹配到的字段，优先更新
                    for sno in STUDENT_NO.findall(b):
                        rec["studentNo"] = sno.decode("utf-8", "replace")
                    for uid in UNIFY_ID.findall(b):
                        rec["unifyId"] = uid.decode("utf-8", "replace")
                    for sname in STUDENT_NAME.findall(b):
                        rec["studentName"] = sname.decode("utf-8", "replace")
                    for dname in DEPT_NAME.findall(b):
                        rec["deptName"] = dname.decode("utf-8", "replace")
                else:
                    other_found.setdefault(oid, {
                        "openid": oid,
                        "unifyId": None,
                        "studentNo": None,
                        "studentName": None,
                        "deptName": None,
                        "source": source,
                        "is_cqupt": False,
                    })

    # 优先返回重邮智慧体育专属身份；若未匹配到专属标识则回退返回全部检测到的 openid
    if cqupt_found:
        return list(cqupt_found.values())
    return list(other_found.values())


def main():
    print("=" * 60)
    print("      重邮智慧体育 · openid 提取器 (Linux / Windows)")
    print("=" * 60)
    print()

    active_roots = [r for r in WECHAT_ROOTS if os.path.isdir(r)]

    # 已知路径 + 自动发现合并。微信 Linux 版允许把数据目录重定向到任意位置，
    # 只靠硬编码清单会漏，所以总要兜底扫一次。
    caches = cache_dirs(active_roots)

    search_roots = [os.path.expanduser("~")]
    # 允许命令行显式指定搜索起点（放在搜索前，避免全盘走一遍）
    for arg in sys.argv[1:]:
        if not arg.startswith("-") and os.path.isdir(arg):
            search_roots.insert(0, os.path.abspath(arg))

    # 关键：已知路径下没扫到结果时也要自动发现。
    # 微信 Linux 版允许把数据目录重定向到任意位置，此时 ~/.xwechat
    # 只是个空壳，真实缓存在别处。只在「没找到缓存目录」时才扫
    # 是不够的 —— 空壳目录会让程序误以为找对了地方。
    need_scan = ("--all" in sys.argv) or (not caches) or (not scan(caches))
    if need_scan:
        scanned = discover_cache_dirs(search_roots)
        added = [d for d in scanned if d not in caches]
        caches.extend(added)
        if scanned:
            print("[*] 自动发现 %d 个缓存目录（含被重定向的数据目录）" % len(scanned))
            shown = scanned if "--verbose" in sys.argv else scanned[:3]
            for d in shown:
                print("      - %s" % d)
            if len(scanned) > len(shown):
                print("      … 其余 %d 个用 --verbose 查看" % (len(scanned) - len(shown)))

    if not caches:
        if not active_roots:
            print("[X] 没找到电脑版微信的数据目录，自动搜索也没有找到微信缓存。")
            print("    检测过的路径：")
            for r in WECHAT_ROOTS:
                print("      - %s" % r)
            print()
            print("    请确保已安装电脑版微信并登录。")
            print("    也可以直接指定微信数据目录，例如：")
            print("      python3 get_openid.py ~/.xwechat")
            return 2
        print("[X] 没找到小程序缓存目录。")
        print("    请在电脑微信里打开一次「重邮智慧体育」小程序，再重试。")
        return 3

    print("[*] 微信数据目录：%s" % (active_roots[0] if active_roots else caches[0]))
    print("[*] 共检查 %d 个小程序缓存目录" % len(caches))
    if "--verbose" in sys.argv:
        for d in caches:
            print("      - %s" % d)

    hits = scan(caches)
    if not hits:
        print("[X] 缓存里没有找到 openid。")
        print("    说明尚未在电脑微信中打开并登录过该小程序。")
        print("    请在电脑微信里打开一次「重邮智慧体育」并完成登录，然后重试。")
        return 4

    is_cqupt = any(h.get("is_cqupt") for h in hits)
    label = "重邮智慧体育身份" if is_cqupt else "检测到的身份"
    print("找到 %d 个%s：" % (len(hits), label))
    print()
    for i, h in enumerate(hits, 1):
        print("  [%d] %s" % (i, label))
        print("      openid:     %s" % h["openid"])
        if h.get("studentName"):
            print("      姓名:       %s" % h["studentName"])
        if h.get("studentNo"):
            print("      学号:       %s" % h["studentNo"])
        if h.get("deptName"):
            print("      学院:       %s" % h["deptName"])
        if h.get("unifyId"):
            print("      统一认证码: %s" % h["unifyId"])
        print()

    if len(hits) == 1:
        print("-" * 60)
        print("把这个 openid 填进 App 的【打卡】页即可：")
        print()
        print("  %s" % hits[0]["openid"])
        print()
        print("-" * 60)

    return 0


if __name__ == "__main__":
    code = 0
    try:
        code = main()
    except Exception as e:
        print("[X] 出错了：%s: %s" % (type(e).__name__, e))
        code = 1
    print()
    if os.name == "nt":
        try:
            input("按回车键关闭…")
        except (EOFError, KeyboardInterrupt):
            pass
    sys.exit(code)
