#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""清掉服务端挂着没结束的残留记录。

用法：
    python clear_stale.py <openid>

openid 用 get_openid.py 取一次就行。也可以设成环境变量 CR_OPENID。
"""
import sys, io, os
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from wecat_run import Client

OPENID = (sys.argv[1] if len(sys.argv) > 1 else os.environ.get("CR_OPENID", "")).strip()
if not OPENID:
    print("用法: python clear_stale.py <openid>")
    print("  或: set CR_OPENID=你的openid 后再运行")
    sys.exit(1)

c = Client(openid=OPENID, verbose=False)
c.acquire_token()

d = (c._call("GET", "/sportRecord/mobile/list/1/12") or {}).get("data") or {}
res = (c._call("GET", "/sportsResult/list") or {}).get("data") or []
done = {r.get("sportsResultNo") for r in res}

stale = []
for it in (d.get("list") or []):
    no = it.get("sportRecordNo")
    info = (c._call("GET", "/sportRecord/info/" + no) or {}).get("data") or {}
    pts = len(info.get("points") or [])
    if pts > 0 and no not in done:
        stale.append((no, pts, info.get("mileage")))

if not stale:
    print("没有挂着的残留记录，干净。")
    sys.exit(0)

print("找到 %d 条挂着的：" % len(stale))
for no, pts, km in stale:
    print("  %s  %d 点 / %s km" % (no, pts, km))

for no, pts, km in stale:
    r = c._call("POST", "/sportRecord/sport/end/" + no) or {}
    ok = r.get("code") == "10200"
    print("  结束 %s -> %s %s" % (no, "✓" if ok else "✗", "" if ok else r.get("msg")))

# 复核
res2 = (c._call("GET", "/sportsResult/list") or {}).get("data") or []
done2 = {r.get("sportsResultNo") for r in res2}
print()
print("成绩表现在 %d 条：" % len(res2))
for r in res2:
    print("  %s  %s  %skm  %ss  %s"
          % (r.get("sportsResultNo"), "✓" if r.get("isValid") == "1" else "✗",
             r.get("distance", 0) and float(r["distance"]) / 1000,
             r.get("duration"), r.get("reason") or ""))
