#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
wecat_run.py —— 重邮智慧体育 打卡客户端（真实协议实现）

协议来源：微信 PC 4.0 小程序缓存逆向，见 refs/FINDINGS.md

链路：
    openid → checkBinding → token
    → getRule / getPlayground / getAreaModelList / getSportPoint
    → sport/start2 → sportRecordNo
    → point/saveListByNo  ×N（每批 8 点，末点作下批首点）
    → sport/end/{sportRecordNo}

用法：
    # 1. 探测：换 token，把规则/场地/区域全拉下来看看
    python wecat_run.py probe --openid OPENID

    # 2. 干跑：只打印将要发送的请求，不发
    python wecat_run.py run --openid OPENID --place 0 --pace 5:30 --dist 2.0 --dry-run

    # 3. 实跑
    python wecat_run.py run --openid OPENID --place 0 --pace 5:30 --dist 2.0 --live
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.parse
import urllib.request
import urllib.error
from datetime import datetime, timedelta

BASE = "https://sport.cqupt.edu.cn/new_wxapp"
POINT_BATCH = 8                 # 对应小程序里的 globalData.pointCount
TIMEOUT = 20


# ------------------------------------------------------------------ HTTP

class Client:
    def __init__(self, openid: str = "", token: str = "", phone: str = "PC_Windows",
                 verbose: bool = True):
        self.openid = openid
        self.token = token
        self.phone = phone
        self.verbose = verbose
        self.log = []

    # ---------- 底层 ----------

    def _call(self, method: str, path: str, params: dict | None = None,
              body: dict | None = None, use_token: bool = True) -> dict:
        url = BASE + path
        if params:
            clean = {k: v for k, v in params.items() if v not in (None, "")}
            qs = urllib.parse.urlencode(clean)
            if qs:
                url += ("&" if "?" in url else "?") + qs

        headers = {"Content-Type": "application/json"}
        if use_token:
            headers["token"] = self.token

        data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
        req = urllib.request.Request(url, data=data, headers=headers, method=method.upper())

        if self.verbose:
            shown = url if not use_token else url
            print(f"    {method.upper():<5} {shown}")
            if body is not None:
                s = json.dumps(body, ensure_ascii=False)
                print(f"          body {len(s)}B: {s[:150]}{'...' if len(s) > 150 else ''}")

        entry = {"method": method.upper(), "url": url, "body": body}
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
                text = r.read().decode("utf-8", "replace")
            entry["status"] = 200
            entry["resp"] = text[:400]
            if self.verbose:
                print(f"          ← {text[:220]}")
            self.log.append(entry)
            try:
                return json.loads(text)
            except json.JSONDecodeError:
                return {"code": "-1", "raw": text}
        except urllib.error.HTTPError as e:
            body_txt = e.read().decode("utf-8", "replace")
            entry["status"] = e.code
            entry["resp"] = body_txt[:400]
            self.log.append(entry)
            print(f"          ← HTTP {e.code}: {body_txt[:220]}")
            return {"code": str(e.code), "msg": body_txt}
        except Exception as e:
            entry["status"] = -1
            entry["resp"] = repr(e)
            self.log.append(entry)
            print(f"          ← {type(e).__name__}: {e}")
            return {"code": "-1", "msg": str(e)}

    # ---------- 认证 ----------

    def check_binding(self) -> dict:
        """wxCode 留空即可，只要有 openid。"""
        return self._call("GET", "/wxUnifyId/checkBinding",
                          params={"wxCode": "", "openid": self.openid, "phoneType": self.phone},
                          use_token=False)

    def acquire_token(self) -> bool:
        r = self.check_binding()
        data = r.get("data") or {}
        if str(data.get("isBind")) == "true" and data.get("token"):
            self.token = data["token"]
            print(f"  [✓] token = {self.token[:16]}...{self.token[-8:]}")
            return True
        print(f"  [×] 换 token 失败: {json.dumps(r, ensure_ascii=False)[:300]}")
        return False

    # ---------- 查询 ----------

    def get_rule(self):
        return self._call("GET", "/setting/getRule")

    def get_playground(self):
        return self._call("GET", "/place/getPlayground")

    def get_area_model_list(self, x=0):
        return self._call("GET", f"/areaModel/listAll/{x}")

    def get_points_by_no(self, place_code):
        return self._call("GET", f"/sportPoint/listAll/{place_code}")

    def get_sport_record_info(self, no):
        return self._call("GET", f"/sportRecord/info/{no}")

    def get_wx_sport_page(self, page_no=1, page_size=8):
        return self._call("GET", f"/sportRecord/mobile/list/{page_no}/{page_size}")

    # ---------- 跑步 ----------

    def start_sport(self, payload: dict):
        return self._call("POST", "/sportRecord/sport/start2", body=payload)

    def upload_points(self, record_no: str, points: list[dict]):
        return self._call("POST", "/sportRecord/point/saveListByNo",
                          body={"sportRecordNo": record_no, "sportPointList": points})

    def end_sport(self, record_no: str):
        return self._call("POST", f"/sportRecord/sport/end/{record_no}")


def fetch_areas(c: Client) -> list[dict]:
    """从 /area/getAllArea 组装可跑步场地。

    同一 placeCode 会有多条记录：
      areaFuncCode == "dzwl" → 电子围栏，轨迹必须在内
      areaFuncCode == "jpq"  → 禁跑区，轨迹必须避开（区内点不计里程）
      areaFuncCode == "dkq"  → 打卡区
    """
    r = c._call("GET", "/area/getAllArea")
    arr = r.get("data") or []
    fences, bans = {}, {}
    for a in arr:
        code = a.get("placeCode")
        func = a.get("areaFuncCode")
        pts = a.get("areaPointList") or []
        poly = [(float(p["latitude"]), float(p["longitude"])) for p in pts]
        if len(poly) < 3:
            continue
        if func == "dzwl" and code not in fences:
            fences[code] = {"placeCode": code, "placeName": a.get("placeName"),
                            "fence": poly, "excludes": []}
        elif func == "jpq":
            bans.setdefault(code, []).append(poly)
    for code, ex in bans.items():
        if code in fences:
            fences[code]["excludes"] = ex
    return list(fences.values())


# ------------------------------------------------------------------ 轨迹 → 点列

def fmt_time(dt: datetime) -> str:
    """小程序用的格式：yyyy-MM-dd HH:mm:ss"""
    return dt.strftime("%Y-%m-%d %H:%M:%S")


def build_points(track: list[dict], record_no: str,
                 place_name: str, place_code: str,
                 start_dt: datetime) -> list[dict]:
    """把轨迹转成小程序协议的点列表。

    collectTime 永远跟着轨迹自己的时间轴走（1 秒一个点），
    和"是否节流上传"无关 —— 否则 690 个点会带同一个时间戳，一眼假。
    """
    t0 = track[0]["t"]
    out = []
    for p in track:
        ct = start_dt + timedelta(seconds=p["t"] - t0)
        out.append({
            "sportRecordNo": record_no,
            "longitude": p["lon"],
            "latitude": p["lat"],
            "placeName": place_name,
            "placeCode": place_code,
            "collectTime": fmt_time(ct),
            "isValid": "1",
        })
    return out


def batch_points(points: list[dict], n: int = POINT_BATCH) -> list[list[dict]]:
    """按小程序的节奏切批：每批 n 个点，且把上一批的末点作为下一批的首点。"""
    batches = []
    cur = []
    for pt in points:
        cur.append(pt)
        if len(cur) >= n:
            batches.append(cur)
            cur = [cur[-1]]        # 保留末点
    if len(cur) > 1:
        batches.append(cur)
    return batches


# ------------------------------------------------------------------ 子命令

def cmd_probe(c: Client, args) -> int:
    print("[*] 1) 换取 token")
    if not c.acquire_token():
        return 1

    print("\n[*] 2) 跑步规则（配速区间）")
    rule = c.get_rule()
    print("   ", json.dumps(rule.get("data", rule), ensure_ascii=False)[:600])

    print("\n[*] 3) 可跑步场地（/area/getAllArea）")
    areas = fetch_areas(c)
    for i, a in enumerate(areas):
        print(f"    [{i}] {a['placeCode']:<7} {a['placeName']:<12} "
              f"围栏 {len(a['fence'])} 顶点"
              + (f" / 禁跑区 {len(a['excludes'])} 块" if a['excludes'] else ""))

    print("\n[*] 4) 历史记录（前 3 条）")
    page = c.get_wx_sport_page(1, 3)
    for r in (page.get("data", {}).get("list") or [])[:3]:
        print("   ", json.dumps({k: r.get(k) for k in
                                 ("sportRecordNo", "mileage", "timeConsuming",
                                  "placeName", "createTime") if k in r},
                                ensure_ascii=False))
    return 0


def cmd_run(c: Client, args) -> int:
    dry = not args.live

    print("[*] 1) 换取 token")
    if not c.acquire_token():
        return 1

    print("\n[*] 2) 拉规则与场地")
    rule = c.get_rule().get("data") or {}
    max_pace, min_pace = rule.get("maxPace"), rule.get("minPace")
    print(f"    配速区间: {min_pace} ~ {max_pace}")

    areas = fetch_areas(c)
    if not areas:
        print("  [×] 没拿到区域，无法确定 placeCode")
        return 1

    if args.place is None:
        for i, a in enumerate(areas):
            print(f"    [{i}] {a['placeCode']:<7} {a['placeName']:<12} "
                  f"围栏 {len(a['fence'])} 顶点"
                  + (f" / 禁跑区 {len(a['excludes'])} 块" if a['excludes'] else ""))
        print("  [×] 用 --place INDEX 选一个（或直接传 placeCode）")
        return 1

    area = None
    if args.place.isdigit() and int(args.place) < len(areas):
        area = areas[int(args.place)]
    else:
        area = next((a for a in areas if str(a["placeCode"]) == args.place), None)
    if not area:
        print(f"  [×] 找不到场地 {args.place}")
        return 1
    place_code = area["placeCode"]
    place_name = area["placeName"]
    print(f"    选定: {place_name} ({place_code})")

    print("\n[*] 3) 生成轨迹（基于服务器下发的真实电子围栏）")
    sys.path.insert(0, args.engine_dir or ".")
    import track_engine as TE
    pace_s = TE.parse_pace(args.pace)
    start_dt = datetime.now()
    track, st = TE.build_track_poly(area["fence"], area["excludes"],
                                    pace_s, args.dist * 1000.0,
                                    noise_sigma=args.noise)
    real = st["duration"] / (st["distance"] / 1000.0)
    tm, ts = divmod(int(pace_s), 60)
    rm, rs = divmod(int(real), 60)
    print(f"    点数 {st['n']} | 距离 {st['distance']} m | 时长 {st['duration']}s | "
          f"实测配速 {rm}'{rs:02d}\"/km (目标 {tm}'{ts:02d}\")")
    print(f"    围栏内 {st['inside']}/{st['n']} | 禁跑区 {st['banned']} 点 | 缩放 {st['inset']}")

    if args.validate_region:
        if st["inside"] < st["n"] * 0.99:
            print("  [!] 有大量点落在电子围栏外，换个场地试试")
            return 1
        if st["banned"] > 0:
            print(f"  [!] 有 {st['banned']} 个点落在禁跑区，这部分不计里程")

    print("\n[*] 4) 开始跑步")
    payload = {
        "placeCode": place_code,
        "placeName": place_name,
        "sportType": args.sport_type,
        "longitude": track[0]["lon"],
        "latitude": track[0]["lat"],
        "collectTime": fmt_time(start_dt),
    }
    if dry:
        print("    [dry] 跳过 start2，sportRecordNo 用占位")
        record_no = "DRYRUN-SPORT-RECORD-NO"
    else:
        r = c.start_sport(payload)
        record_no = (r.get("data") or {}).get("sportRecordNo") or \
                    (r.get("data") or {}).get("no")
        if not record_no:
            print(f"  [×] start2 没返回 sportRecordNo: {json.dumps(r, ensure_ascii=False)[:400]}")
            return 1
        print(f"    sportRecordNo = {record_no}")

    print("\n[*] 5) 上传轨迹点")
    pts = build_points(track, record_no, place_name, place_code, start_dt)
    batches = batch_points(pts, POINT_BATCH)
    print(f"    共 {len(pts)} 点 → {len(batches)} 批（每批 {POINT_BATCH}，末点作下批首点）")

    for i, b in enumerate(batches):
        print(f"\n  --- 批次 {i+1}/{len(batches)} ({len(b)} 点) ---")
        if dry:
            first, last = b[0], b[-1]
            print(f"    [dry] {first['collectTime']} → {last['collectTime']}")
            continue
        c.upload_points(record_no, b)
        if args.realtime and i < len(batches) - 1:
            time.sleep(args.delay)

    print("\n[*] 6) 结束跑步")
    if dry:
        print("    [dry] 跳过 end")
    else:
        c.end_sport(record_no)
        info = c.get_sport_record_info(record_no)
        print("    记录详情:", json.dumps(info.get("data", info), ensure_ascii=False)[:400])

    if args.dump_log:
        with open(args.dump_log, "w", encoding="utf-8") as f:
            json.dump(c.log, f, ensure_ascii=False, indent=1)
        print(f"\n[+] 请求日志 -> {args.dump_log}")
    return 0


def point_in_poly(lat: float, lon: float, poly: list[dict]) -> bool:
    """射线法，和小程序里的 IsPtInPoly 等价。"""
    n = len(poly)
    if n < 3:
        return False
    inside = False
    for i in range(n):
        a = poly[i]
        b = poly[0] if i == n - 1 else poly[i + 1]
        la, lo = a["latitude"], a["longitude"]
        lb, lob = b["latitude"], b["longitude"]
        if (lat >= la and lat < lb) or (lat >= lb and lat < la):
            if abs(la - lb) > 0:
                if lo - (lo - lob) * (la - lat) / (la - lb) < lon:
                    inside = not inside
    return inside


# ------------------------------------------------------------------ main

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="wecat_run", description="重邮智慧体育 打卡客户端")
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common(p):
        p.add_argument("--openid", default="", help="小程序 openid（必需）")
        p.add_argument("--token", default="", help="已有 token 可直传，跳过 checkBinding")
        p.add_argument("--phone", default="PC_Windows", help="phoneType，如 PC_Windows")

    p_probe = sub.add_parser("probe", help="探测：换 token 并把规则/场地/区域/历史拉下来")
    common(p_probe)
    p_probe.set_defaults(func=cmd_probe)

    p_run = sub.add_parser("run", help="完整打卡")
    common(p_run)
    p_run.add_argument("--place", default=None, help="区域索引或 placeCode")
    p_run.add_argument("--route", default="A", help="track_engine 的路线模板")
    p_run.add_argument("--pace", default="5:30", help="配速")
    p_run.add_argument("--dist", type=float, default=2.0, help="距离 km")
    p_run.add_argument("--noise", type=float, default=0.6)
    p_run.add_argument("--sport-type", default="1")
    p_run.add_argument("--realtime", action="store_true",
                       help="按真实配速节流上传（默认不节流，一把梭）")
    p_run.add_argument("--delay", type=float, default=30.0,
                       help="realtime 模式下每批之间的间隔秒")
    p_run.add_argument("--validate-region", action="store_true",
                       help="上传前检查轨迹是否落在所选区域多边形内")
    p_run.add_argument("--engine-dir", default=None, help="track_engine.py 所在目录")
    p_run.add_argument("--dry-run", action="store_true", help="只打印不发送（默认）")
    p_run.add_argument("--live", action="store_true", help="真的发送")
    p_run.add_argument("--dump-log", default=None, help="把请求日志写到 JSON")
    p_run.set_defaults(func=cmd_run)

    args = ap.parse_args(argv)
    if not args.openid and not args.token:
        print("[×] 至少要给 --openid 或 --token")
        return 1

    c = Client(openid=args.openid, token=args.token, phone=args.phone)
    return args.func(c, args)


if __name__ == "__main__":
    raise SystemExit(main())
