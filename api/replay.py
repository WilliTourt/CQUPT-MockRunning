#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
campusrun / api / replay.py

路线 D：绕开 GPS，直接重放后端接口。

适用条件
    抓包能拿到"上传轨迹"的 HTTPS 请求，且签名/加密可还原。

侦察顺序
    1. PC 微信打开小程序，Charles / mitmproxy 代理 WeChatAppEx.exe
       小程序 HTTPS 走系统代理，通常不需要 pinning 绕过
       若遇到 pinning：Frida hook SSL_write / X509TrustManager
    2. 在小程序里完整跑一次（或点到"结束跑步"），捞出这些请求：
         login/authorize      -> 拿 token
         run/start            -> 开始打卡，通常返回一个 runId/sessionId
         run/upload / run/end -> 上传轨迹点
         run/report           -> 打卡结果
    3. 逐字段比对：如果是明文，直接改数据重放；如果签名，还原算法。

常见签名形态（按出现频率）
    * header:  X-Sign / X-Signature / sign / timestamp+nonce
    * md5(path + sorted_params + secret) 或 HMAC-SHA256(key, body)
    * 微信侧可能是 wx.login 的 code -> 后端换 session_key，再拿 session_key 当 HMAC key
    * body 整体 AES-CBC 加密，key 硬编码在小程序 JS 里

还原方法
    PC 端小程序代码在：
      %APPDATA%\\Tencent\\WeChat\\Applet\\<appid>\\<版本号>\\__APP__.wxapkg
    用 wxappUnpacker / unveilr 解开 -> 搜 "sign" / "encrypt" / "aes" / "wx.request"

用法
    python replay.py --config cfg.json --dry-run
    python replay.py --config cfg.json --live
"""

from __future__ import annotations

import argparse
import json
import time
import sys
import urllib.request
import urllib.error

# ------------------------------------------------------------------ 占位

CFG_TEMPLATE = {
    "base": "https://API_HOST",
    "token": "TOKEN",
    "run_id": None,
    "endpoints": {
        "start":  "/api/run/start",
        "upload": "/api/run/upload",
        "end":    "/api/run/end"
    },
    "headers": {
        "User-Agent": "Mozilla/5.0 (Linux; Android 13) MicroMessenger/8.0.40",
        "Content-Type": "application/json",
        "Authorization": "Bearer TOKEN",
        "X-Sign": "SIGN_PLACEHOLDER"
    },
    "sign": {
        "algo": "md5",              # md5 | hmac_sha256 | none
        "secret": "SECRET",
        "template": "{path}{body}{secret}",
        "sorted_query": False
    },
    "track_json": "track.json",
    "chunk": 30,                     # 每批上传点数
    "delay": 3.0,                    # 批次间隔秒，模拟真实上报节奏
    "body_map": {
        "lat": "latitude",
        "lon": "longitude",
        "t": "timestamp",
        "speed": "speed",
        "accuracy": "accuracy"
    }
}


def load_track(path: str) -> list[dict]:
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    return data["points"] if isinstance(data, dict) else data


def build_body(pt: dict, m: dict) -> dict:
    out = {}
    for src, dst in m.items():
        if src in pt:
            out[dst] = pt[src]
    return out


def sign_payload(cfg: dict, path: str, body: str) -> str:
    s = cfg.get("sign", {})
    algo = s.get("algo", "none")
    if algo == "none":
        return ""
    tpl = s.get("template", "{path}{body}{secret}")
    raw = tpl.format(path=path, body=body, secret=s.get("secret", ""))
    if algo == "md5":
        import hashlib
        return hashlib.md5(raw.encode()).hexdigest()
    if algo == "hmac_sha256":
        import hmac
        import hashlib
        return hmac.new(s["secret"].encode(), raw.encode(), hashlib.sha256).hexdigest()
    raise ValueError(f"未知签名算法 {algo}")


def post(cfg: dict, path: str, payload: dict, dry: bool) -> dict | None:
    body = json.dumps(payload, separators=(",", ":"), ensure_ascii=False)
    url = cfg["base"].rstrip("/") + path
    headers = dict(cfg.get("headers", {}))

    sig = sign_payload(cfg, path, body)
    for k in list(headers):
        if k.lower() in ("x-sign", "x-signature", "sign") and sig:
            headers[k] = sig

    if dry:
        print(f"  [dry] POST {url}")
        print(f"        sign={sig}")
        print(f"        body={body[:160]}{'...' if len(body) > 160 else ''}")
        return None

    req = urllib.request.Request(url, data=body.encode(), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            text = r.read().decode("utf-8", "replace")
        print(f"  [{r.status}] {text[:200]}")
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            return {"raw": text}
    except urllib.error.HTTPError as e:
        print(f"  [!] HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:200]}")
    except Exception as e:
        print(f"  [!] {type(e).__name__}: {e}")
    return None


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default="cfg.json")
    ap.add_argument("--init", action="store_true", help="生成配置模板")
    ap.add_argument("--track", default=None, help="覆盖配置里的轨迹文件")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--live", action="store_true")
    ap.add_argument("--start-only", action="store_true")
    args = ap.parse_args(argv)

    if args.init:
        with open("cfg.json", "w", encoding="utf-8") as f:
            json.dump(CFG_TEMPLATE, f, ensure_ascii=False, indent=2)
        print("[+] 已生成 cfg.json，填入抓包得到的真实值")
        return 0

    # utf-8-sig：兼容 Windows 记事本 / PowerShell 写出来的带 BOM 文件
    with open(args.config, "r", encoding="utf-8-sig") as f:
        cfg = json.load(f)

    dry = not args.live
    ep = cfg["endpoints"]
    m = cfg["body_map"]

    print("[*] 1/3 开始打卡")
    r = post(cfg, ep["start"], {"ts": int(time.time() * 1000)}, dry)

    if args.start_only:
        return 0

    run_id = None
    if isinstance(r, dict):
        run_id = r.get("runId") or r.get("data", {}).get("runId") if isinstance(r.get("data"), dict) else None
    run_id = run_id or cfg.get("run_id")
    print(f"    run_id = {run_id}")

    pts = load_track(args.track or cfg["track_json"])
    print(f"[*] 2/3 上传轨迹 {len(pts)} 点，每批 {cfg['chunk']} 点")

    for i in range(0, len(pts), cfg["chunk"]):
        chunk = [build_body(p, m) for p in pts[i:i + cfg["chunk"]]]
        payload = {"runId": run_id, "points": chunk}
        post(cfg, ep["upload"], payload, dry)
        if not dry:
            time.sleep(cfg.get("delay", 3.0))

    print("[*] 3/3 结束打卡")
    post(cfg, ep["end"], {"runId": run_id, "ts": int(time.time() * 1000)}, dry)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
