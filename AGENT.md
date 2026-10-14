# AGENT.md

本文件是给 AI 编码助手（以及新加入的人类协作者）看的项目说明书。
**动手改代码之前先读完，提交之前必须执行第 6 节的检查。**

---

## 1. 项目基本信息

| 项 | 内容 |
|---|---|
| 名称 | 鲸鲸约跑（CampusRun） |
| 一句话 | 微信小程序「重邮智慧体育」的打卡工具，**安卓单机版** |
| 作者 | WilliTourt的蓝色大肥鱼 🐟 |
| 当前版本 | **v1.9.1**（`versionCode 10901` / `versionName "1.9.1"`）—— 由版本分支 `V1.9.1` 推导，规则见 [§5.1](#51-版本号规则semver-20) |
| License | MIT |
| 仓库 | https://github.com/gufandf/CQUPT-MockRunning |
| 目标服务 | `https://sport.cqupt.edu.cn/new_wxapp`（重邮智慧体育小程序后端） |

**设计原则（改代码时不要破坏）：**

- **不申请任何定位权限、不连自建服务器、不需要登录。**
  权限只用这几项，不要加新的：`INTERNET`、`ACCESS_NETWORK_STATE`、`FOREGROUND_SERVICE(_DATA_SYNC)`、
  `POST_NOTIFICATIONS`（前台服务通知，Android 13+）、`WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。
- 直接构造 HTTP 请求，**不碰手机 GPS**。早期 iOS / Android 定位注入方案已作废，仅留作参考。
- **零第三方网络依赖。** Android 侧只用 `HttpURLConnection` + `org.json`，不要引入 Retrofit / OkHttp / Gson。
- 反面事实：人脸检测在**操场物理摄像头**上做，工具**绕不过去**，只能靠本人到场。

---

## 2. 技术栈

**Android App（主交付物）**

- Kotlin 1.9.24 + Android Gradle Plugin 8.5.2，Gradle 8.7（wrapper 已带 jar）
- `compileSdk 34` / `minSdk 24` / `targetSdk 34` / JDK 17
- AppCompat `DayNight` 主题，颜色**全部走资源引用**，不要写死色值
- Release 直接用 debug 签名（自用项目，不配 keystore）

**Python 工具链**

- 仅标准库 + `requests`（`wecat_run.py` 需要）；Python 3
- 无打包、无 `requirements.txt`，脚本直接跑

---

## 3. 目录结构

```
CQUPT-MockRunning/
├── android-app/              📱 成品 App（主交付物，绝大多数改动在这里）
│   ├── app/src/main/java/com/whale/campusrun/
│   │   ├── Api.kt            重邮智慧体育 API 客户端（无签名、明文 JSON）
│   │   ├── App.kt            Application 入口，只负责在任何 Activity 前定好主题
│   │   ├── AreaStore.kt      场地缓存单例：设置页拉到 → 主页 onResume 直接取用
│   │   ├── MainActivity.kt   主页，**只放打卡**（openid / 场地 / 配速 / 仪表盘 / 日志）
│   │   ├── SettingsActivity.kt  设置页：主题 / 统计 / 取码 / 教程 / 说明 / 开源仓库
│   │   ├── RunController.kt  单例，持有跑步线程，切主题重建 Activity 不丢进度
│   │   ├── RunEngine.kt      打卡状态机：换 token → 生成轨迹 → start2 → 分批上传 → end
│   │   ├── RunService.kt     前台服务，保证息屏后台不被打死
│   │   └── TrackGen.kt       轨迹生成（纯 JVM 逻辑，可单测）
│   ├── app/src/main/assets/get_openid.py   随 App 分发的取码脚本（与根目录同源）
│   └── app/src/test/java/.../TrackGenTest.kt  用服务器真实多边形做断言
│                              AreaStoreTest.kt 场地缓存的状态机（拉取/失败/换账号）
├── get_openid.py             本地提取 openid（读微信 HTTP 缓存，零解密、不联网）
├── wecat_run.py              命令行版客户端
├── track_engine.py           轨迹引擎 Python 参考实现
├── refs/                     逆向报告与一次性分析脚本
│   ├── FINDINGS.md           ⭐ 完整逆向报告（20+ 接口、包体解密、实测日志）
│   ├── wxapkg_unpack.py      V1MMWX 包体解密 + 解包
│   ├── clear_stale.py        清理服务端挂着的未结束记录
│   ├── verify_trackgen.py    TrackGen.kt 的 Python 镜像（配速标定 / 围栏判定）
│   └── verify_pace_profile.py  配速形态验证（3 场地 × 3 形态 × 4 配速 × 8 seed）
├── android/ · ios/ · api/    早期作废的定位注入方案，仅作参考，不要在此基础上开发
├── docs/img/                 README 用的截图
├── README.md                 面向使用者的说明
└── CHANGELOG.md              版本变更记录（Keep a Changelog 格式）
```

**界面结构约定（v1.9.0 起）**：主页只放打卡相关内容；主题、统计、取码、教程、
说明、开源仓库一律放 `SettingsActivity`。新增非打卡功能请加到设置页，不要堆回主页。

---

## 4. 构建 / 测试 / 运行

```bash
# 构建 Release APK（需要 JDK 17 + Android SDK 34）
cd android-app && ./gradlew assembleRelease        # Windows 用 gradlew.bat
# 产物：android-app/app/build/outputs/apk/release/

# 单元测试（TrackGen 配速标定 + 围栏判定，纯 JVM，不需要设备）
cd android-app && ./gradlew :app:testDebugUnitTest
# 报告：android-app/app/build/reports/tests/testDebugUnitTest/index.html

# 命令行版
pip install requests
python wecat_run.py probe --openid <OPENID>
python wecat_run.py run --openid <OPENID> --place 0 --pace 5:45 --dist 2.0 --realtime --live

# 轨迹引擎单独验证
python track_engine.py gen --pace 5:45 --dist 2.0 --crs gcj02 --out track.json
```

**改动 `TrackGen.kt` 后必须跑 `testDebugUnitTest`**，它会断言这几条硬指标：
实测配速 == 目标配速（误差 ≤ 1 秒）、所有点落在电子围栏内、没有点落在禁跑区、
**平均配速始终落在「目标 ± 容差」窗口内**、各种配速与种子下都不能越界、
温和起伏既要有快慢差异也要有停顿、汇报速度与实际位移一致。

改轨迹算法时，另外跑一遍 Python 镜像做交叉验证（不需要 Android SDK）：

```bash
python refs/verify_trackgen.py        # 配速标定 + 围栏判定
python refs/verify_pace_profile.py    # 配速起伏 × 3 场地 × 4 配速
```

**界面结构约定**：主页只放打卡相关内容；openid、主题、统计、取码、教程、说明、
开源仓库一律放 `SettingsActivity`，运行日志放 `LogActivity`。
新增非打卡功能请加到设置页，不要堆回主页。两个页面都要有固定顶栏
（标题 + 返回箭头），顶栏图标用 `app:tint="@color/fg"`（**不要**用 `android:tint`，
lint 会报 `UseAppTint`）。主页顶栏图标：日志 `ic_log`、设置 `ic_settings`。
状态栏用 `@color/box` 与顶栏连成一体，底部导航栏用 `@color/bg`。

---

## 5. 代码约定

- 注释和用户可见文案一律用中文；注释写**「为什么这么做」**，不写「这行在干什么」。
- **版本号必须按 5.1 的规则从版本分支推导**，不要凭感觉手写；要改哪些文件见 5.2 的清单。
- 不引入新依赖前先想清楚 —— 这个项目的卖点之一就是零依赖。
- 反编译 / 抓包得到的新结论写进 `refs/FINDINGS.md`，别散落在聊天记录里。
- 一次性的分析脚本可以放在 `refs/`，但产物（解包目录、strings dump）不要提交，见 `.gitignore`。

### 5.1 版本号规则（SemVer 2.0）

版本号**不是随手写的，而是从「当前开发分支所属的版本分支」引用过来的**：
仓库里的 `V1.9.0` / `V2.0.0` 这类分支就是版本源，`main` 与 `dev-*` 都是它的下游。

**引用来源 —— 按下面的顺序定出唯一的版本分支：**

1. 快进关系：找出祖先里有 `HEAD` 的版本分支（`git merge-base --is-ancestor <分支> HEAD`），
   其中**离 `HEAD` 最近**的那个就是当前版本分支。
2. 并列分叉时，取 `<major>.<minor>.<patch>` 数值最大的一个（用 `sort -V`，不要按字典序）。
3. 若一个都没有（新开的开发分支还没挂到任何版本分支上），
   **不要猜版本号**：要么先按「开新版本」建好版本分支再动版本，
   要么沿用上一版的 `versionName` 并在 `CHANGELOG` 的 `[未发布]` 段落记录改动。

**语义化版本 2.0（`MAJOR.MINOR.PATCH`）—— 只增不减，一旦提交不再修改：**

| 位置 | 什么时候动 | 例子 |
|---|---|---|
| `MAJOR` | 对外行为或数据格式**不兼容**的改动（老配置/老流程会坏掉） | 1.9.0 → 2.0.0 |
| `MINOR` | **向后兼容**地加功能、加选项 | 1.9.0 → 1.10.0 |
| `PATCH` | **向后兼容**地修缺陷，不改接口与行为约定 | 1.9.0 → 1.9.1 |

- 预发布/构建元数据按标准写法 `1.9.0-rc.1`、`1.9.0+build.5`，
  **`versionName` 可以用，`versionCode` 一律取主干三段**（见下）。
- `MINOR` 是数字不是字符串：`1.10.0` 比 `1.9.0` 新，比较时用 `sort -V`。
- 版本分支命名 = `V` + `versionName`，三段齐全（`V1.9.0`，不要写 `V1.9`）。

**映射到 Android（三段必须同源，改一处就要改全部）：**

```kotlin
versionName = "1.9.0"                       // 版本分支名去掉前导 V，原样照抄
versionCode = 10900                         // MAJOR*10000 + MINOR*100 + PATCH
```

`versionCode` 用这个公式是为了让**数字大小和版本新旧一致**（Google Play 只认单调递增），
`MINOR`/`PATCH` 各留两位，`1.9.0 → 10900`、`1.10.0 → 11000`、`2.0.0 → 20000`。
`versionCode` 前补零并无意义，它是整数。

<details>
<summary>一条命令定出版本（在 <code>android-app/</code> 下跑）</summary>

```bash
cur=$(git rev-parse --abbrev-ref HEAD)
base=$(for b in $(git branch --list 'V[0-9]*' --format='%(refname:short)'); do
         git merge-base --is-ancestor "$b" HEAD 2>/dev/null &&
           printf '%s %s %s\n' "$(git rev-list --count "$b"..HEAD)" "${b#V}" "$b"
       done | sort -k1,1n -k2,2rV | head -1 | awk '{print $3}')
echo "分支 $cur → 版本分支 ${base:-<无>}"
IFS=. read -r M m p <<< "${base#V}"
printf 'versionName = "%s.%s.%s"\nversionCode = %d\n' "$M" "$m" "$p" "$((M*10000 + m*100 + p))"
```

> 取「离 HEAD 最近」用的是 `git rev-list --count <版本分支>..HEAD`（越小越近），
> 并列时用 `sort -V` 取版本最大者，与规则 1、2 完全一致。

</details>

### 5.2 改版本号时必须同步的位置

| 位置 | 改什么 |
|---|---|
| `android-app/app/build.gradle.kts` | `versionCode`（公式值）、`versionName`（版本分支） |
| `android-app/app/src/main/AndroidManifest.xml` | `<meta-data android:name="version">` = `versionName` |
| `CHANGELOG.md` | 新增 `## [x.y.z] — 日期 · 摘要` 段落，并在文末补对应的引用式链接 |
| `AGENT.md` §1 表格 | 「当前版本」一行 |
| `README.md` / `android-app/README.md` | 头部「当前版本」标记与版本记录段落 |

**不要**在代码里写死版本号：界面显示一律用 `BuildConfig.VERSION_NAME`
（`MainActivity.kt` / `SettingsActivity.kt` 已经这么做），文案里出现的小版本号
（如「v1.9 起」）可以保留，它描述的是历史事实，不必跟着改。

---

## 6. ⚠️ 提交前强制检查：个人信息与密钥泄露

> **规则：每一次 `git commit` 之前，必须完成本节全部检查。**
> 本项目要接触真实 openid、token、学号和统一认证码，还要处理微信缓存 dump，
> **一次误提交就会把本人身份永久写进 Git 历史**（即使后续删除，历史里仍在）。

### 6.1 三条必跑命令

```bash
# ① 扫真实 openid（28 位、o 开头）。README/FINDINGS 里的 oXXXX... 是占位符，正常
git grep -nE '\bo[A-Za-z0-9_-]{27}\b' -- . | grep -v 'X\{20,\}'

# ② 扫密钥 / 口令 / 私钥
git grep -nEi '(BEGIN [A-Z ]*PRIVATE KEY|api[_-]?key["'"'"']?\s*[:=]|secret["'"'"']?\s*[:=]|passwd|password["'"'"']?\s*[:=])'

# ③ 扫未被忽略的敏感文件（必须无输出）
git status --porcelain --ignored | grep -E 'openid_extract|openid_hunt|\.flow$|wechat_cache|local\.properties|\.jks|\.keystore'
```

更稳妥的做法是连**待提交内容**一起看，而不只是工作区：

```bash
git diff --cached | grep -nEi '(openid|token|secret|password|学号|统一认证)'
```

### 6.2 检查清单（逐条确认）

- [ ] **openid** —— 真实值**绝不允许**出现在任何被跟踪的文件里（含代码、注释、README、
      FINDINGS、测试数据、日志样例、提交信息、截图）。文档里一律写 `oXXXXXXXXXXXXXXXXXXXXXXXXXXX`。
- [ ] **token / sportRecordNo** —— 真实响应体、`trace` 输出、抓包记录不得提交。
- [ ] **学号 / 姓名 / 手机号 / 统一认证码 / 身份证** —— 任何真实个人信息不得提交。
- [ ] **密钥类** —— API Key、`secret`、私钥、keystore / jks、签名口令、`local.properties`（内含本机 SDK 路径）。
- [ ] **截图** —— `docs/img/*.png` 里不能出现真实 openid、学号、真实姓名或聊天记录。
      换图时重点看状态栏和输入框。
- [ ] **提交信息** —— 不要写「用 xxx 的 openid 测试」这类内容。
- [ ] **单测 / 测试数据** —— 用真实下发的场地多边形没问题（是公开地理信息），
      但**不能**带真实账号数据。

### 6.3 已经受 `.gitignore` 保护、永远不要提交的东西

```
refs/openid_extract.txt     真实 openid / 统一认证码 / 学号
refs/openid_hunt.txt        同上
refs/*.flow                 抓包记录
refs/wechat_cache/          微信缓存拷贝
refs/unpacked/              小程序解包结果（可用 wxapkg_unpack.py 重现）
refs/collector_strings*.txt 采集器 strings dump
run/                        运行输出
local.properties            Android 本机 SDK 路径
*.keystore  *.jks           签名材料
```

> **不要**为了方便而把上述文件用 `git add -f` 强行加进去。
> 也不要为了「让某次构建能过」而放宽 `.gitignore` —— 加白名单只允许 `!` 反选**目录内的样板文件**。

### 6.4 万一已经提交了

1. **不要**用一次「删除 + 新提交」了事 —— 历史里的 blob 依然存在，`git log -p` 能翻出来。
2. 立刻作废该凭据：openid 的补救方式是**换绑 / 重新登录**，让旧凭证失效。
3. 改写历史（`git filter-repo` / BFG）后**强推**，并通知所有协作者重新克隆。
4. 如果已推到公开远端，按「已泄露」处理，默认视为不可撤销。

---

## 7. 合规与免责

本项目仅供**个人学习与安全研究**。逆向、接口调用、自动打卡均由使用者自行发起，
使用者需自行承担一切后果（包括但不限于账号处罚、成绩判定、校纪校规处理）。
贡献代码即表示你理解并接受 `LICENSE` 中的免责条款。
