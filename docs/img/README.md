# README 截图

实机拍摄（HarmonyOS，浅色主题）。原图 1224×26xx，处理成 720 宽 + 调色板量化。

| 文件 | 内容 | 拍摄要点 |
|---|---|---|
| `01-run.png` | 【打卡】页，未连接 | openid 输入框（空）、配速 5:45、距离 2.0、红色电池条、开始按钮 |
| `02-stats.png` | 【统计】页 | 数字已打码 |
| `03-help.png` | 【取码】页 | 说明 + 复制/分享脚本按钮 + 行数统计 |
| `04-running.png` | 【打卡】页跑动中 | 绿色电池条 + 实时仪表盘（5% / 0.10 km / 批次 5/99） |
| `05-battery-bad.png` | 电池条 · 红色 | 未加白名单 |
| `06-battery-ok.png` | 电池条 · 绿色 | 已加白名单（**注意：没有 ✅，v1.8 去掉了**） |
| `07-log.png` | 运行日志 | 完整 ①~⑥ 流程，含「记录干净，从 0 开始」 |

## 拍摄时做的脱敏

- openid 输入框留空
- 日志里的 `token =` 打了码
- `sportRecordNo` 打了码
- 统计页所有数字打了码

## 重新处理

原图放一边，改 [`../../refs/icon/process_shots.py`](../../refs/icon/process_shots.py)
里的 `SRC` / `MAP` 后重跑即可。

```bash
python ../../refs/icon/process_shots.py
```

## 显示尺寸

README 里用 HTML `<img>` 控制宽度，不用 markdown 图片语法
（markdown 没法限宽，竖屏截屏会撑满整个页面）：

```html
<img src="docs/img/01-run.png" width="320">
```
