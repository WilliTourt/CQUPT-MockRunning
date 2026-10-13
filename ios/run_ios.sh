#!/usr/bin/env bash
# campusrun / ios / run_ios.sh
#
# iOS 定位注入（推荐路线，免越狱，系统级，App 侧不可检测）
#
# 原理：pymobiledevice3 走 Apple 的 com.apple.dt.simulatelocation 服务，
#       直接把位置写进系统定位源。对 App 而言这就是真实 GPS，
#       Location.isSimulated 不会被置位（与 Android mock provider 不同）。
#
# 依赖：
#   pipx install pymobiledevice3        # 或 pip install pymobiledevice3
#   brew install libimobiledevice       # 可选，用于看设备状态
#
# 前置：iPhone 用数据线连 Mac/PC，设备上"信任此电脑"。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PY="${PYTHON:-python3}"

ROUTE="${1:-A}"
PACE="${2:-5:30}"
DIST="${3:-2.0}"

WORK="$ROOT/run"
mkdir -p "$WORK"
GPX="$WORK/track.gpx"
JSON="$WORK/track.json"

# ---------- 0. 环境自检 ----------
echo "[*] 检查 pymobiledevice3"
if ! command -v "$PY" -m pymobiledevice3 >/dev/null 2>&1 && ! "$PY" -c "import pymobiledevice3" 2>/dev/null; then
  echo "[!] 未安装 pymobiledevice3，执行： $PY -m pip install pymobiledevice3"
  exit 1
fi

# ---------- 1. 生成轨迹（带时间的 GPX，回放速度 == 配速） ----------
echo "[*] 生成轨迹 route=$ROUTE pace=$PACE dist=${DIST}km"
"$PY" "$ROOT/track_engine.py" gen \
  --route "$ROUTE" --pace "$PACE" --dist "$DIST" \
  --out "$JSON" --gpx "$GPX"

# ---------- 2. 建立隧道路由（iOS 17+ 必须） ----------
IOS_MAJOR="$("$PY" -m pymobiledevice3 lockdown info 2>/dev/null \
  | grep -i productversion | grep -oE '[0-9]+' | head -1 || echo 0)"

TUNNEL_PID=""
RSD=""
if [ "${IOS_MAJOR:-0}" -ge 17 ]; then
  echo "[*] iOS ${IOS_MAJOR} >= 17，启动 RemoteXPC 隧道（需要 sudo）"
  sudo "$PY" -m pymobiledevice3 remote tunneld --script-mode > "$WORK/tunnel.txt" 2>&1 &
  TUNNEL_PID=$!
  sleep 4
  RSD="$(grep -oE '[0-9a-f-]+\.(rsd|coredevice)' "$WORK/tunnel.txt" | head -1 || true)"
fi

cleanup() {
  echo "[*] 归还真实定位"
  "$PY" -m pymobiledevice3 developer dvt simulate-location clear ${RSD:+--rsd "$RSD"} 2>/dev/null || true
  [ -n "$TUNNEL_PID" ] && kill "$TUNNEL_PID" 2>/dev/null || true
}
trap cleanup EXIT

# ---------- 3. 回放 ----------
# 关键时序：先在小程序里点"开始跑步"，等它进入定位中，再执行下面这条。
# 想反过来（先铺位置再点开始）也行，simulate-location 是持续的。
echo "[*] 现在去小程序点【开始跑步】，5 秒后开始注入..."
sleep 5

echo "[*] 沿 GPX 回放，按真实时间推进（约 $("$PY" - <<EOF
import sys
pace="$PACE"
m,s=(pace.split(":")+["0"])[:2]
sec=int(m)*60+int(s)
print(f'{sec*float("${DIST}")/60:.1f} 分钟')
EOF
)）"

"$PY" -m pymobiledevice3 developer dvt simulate-location play "$GPX" ${RSD:+--rsd "$RSD"}

# ---------- 4. 收尾 ----------
# play 结束位置会停在终点。建议在跑步完成、打卡成功后再 clear，
# 或者在结束前挪到真实位置，避免后续 App 读到异常坐标。
echo "[+] 回放结束。若已打卡成功，Ctrl-C 退出以归还真实定位。"
read -r -p "按回车归还真实定位..." _
