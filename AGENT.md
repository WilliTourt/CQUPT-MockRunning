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
| 当前版本 | **v1.11.0**（`versionCode 11100` / `versionName "1.11.0"`）—— 每版一个 tag，规则见 [§5.1](#51-版本号规则semver) |
| License | MIT |
| 仓库 | https://github.com/WilliTourt/CQUPT-MockRunning |
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
│   │   ├── AreaStore.kt      场地缓存单例：上手页拉到 → 主页 onResume 直接取用
│   │   ├── MainActivity.kt   **唯一的主界面**：共享顶栏 + 四个 panel + 悬浮切换栏
│   │   ├── panel_*.xml      四页的布局（run / stats / start / settings），被 activity_main include
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
│   └── verify_pace_profile.py  配速形态验证（3 场地 × 4 配速 × 3 seed + 起伏/容差）
├── android/ · ios/ · api/    早期作废的定位注入方案，仅作参考，不要在此基础上开发
├── docs/img/                 README 用的截图
├── README.md                 面向使用者的说明
└── CHANGELOG.md              版本变更记录（Keep a Changelog 格式）
```

**界面结构约定（v1.9.2 起）**：主界面是**一个 Activity + 四个 panel**，底部悬浮栏只切 `visibility`。四页分工：

| 页 | 放什么 |
|---|---|
| 主页 `panel_run` | 场地 / 配速 / 距离 / 容差 / 毛刺开关 / 电池提示 / 开始 / 仪表盘 / 最后一行日志 |
| 统计 `panel_stats` | 成绩汇总 + 分场地 + 最近记录 |
| 上手 `panel_start` | 使用教程 / openid / 取码脚本 |
| 设置 `panel_settings` | 主题 / 说明 / 开源仓库 / 署名 |

新增非打卡功能请加到【设置】页，不要堆回主页。

**不要为了加一页去新建 Activity** —— 四个 panel 的 `id` 全局唯一，`findViewById` 就能拿到，
切页只改 `visibility`（悬浮栏、各页的滚动位置、正在跑的打卡都不受影响）。
运行日志是例外，它是独立的一页 `LogActivity`，从顶栏右上角小图标进入。

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
温和起伏既要有快慢差异也要有停顿、汇报速度与实际位移一致，
以及 **路线必须有毛刺/突起**（跳点数量、最大跳距、每秒转向角都有下限，
不是把椭圆磨圆就完事）。**毛刺开关的两种模式都要测**：关闭时是 v1.10.1 光滑轨迹
（5m+ 跳点为 0），打开后才有突起，且同一 seed 下坐标必须不同。

改轨迹算法时，另外跑一遍 Python 镜像做交叉验证（不需要 Android SDK）：

```bash
python refs/verify_trackgen.py        # 配速标定 + 围栏判定
python refs/verify_pace_profile.py    # 配速起伏 × 3 场地 × 4 配速
python refs/verify_real_areas.py      # 三个真实场地 + 真实 jpq 禁跑区
```

**界面结构约定**：见 §3 的分工表。四页在 `activity_main.xml` 里用 `<include>` 拼起来，
每页的根 `ScrollView` 带 `android:id="@+id/panelXxx"`，`MainActivity.showTab()` 切 `visibility`。
运行日志是独立的 `LogActivity`。

顶栏只有一个（四页共用，切换时不动），里面放标题和日志图标 `ic_log`；
**图标一律用 `app:tint="@color/fg"`**（不要用 `android:tint`，lint 会报 `UseAppTint`）。
悬浮切换栏用 `seg_container`（圆角卡片）+ `android:elevation`，选中项走 `seg_selected`。
每个 panel 的 `ScrollView` 要留 `android:paddingBottom="92dp"` —— 悬浮栏 56dp + 下边距 16dp，
不留的话最后一屏内容会被盖住。
状态栏用 `@color/box` 与顶栏连成一体，底部导航栏用 `@color/bg`。

---

## 5. 代码约定

- 注释和用户可见文案一律用中文；注释写**「为什么这么做」**，不写「这行在干什么」。
- **版本号按 5.1 的语义化规则进位**（该动 MAJOR / MINOR / PATCH 哪一位），
  要改哪些文件见 5.2 的清单。**不需要建版本分支** —— 发布时打 tag 就行。
- 不引入新依赖前先想清楚 —— 这个项目的卖点之一就是零依赖。
- 反编译 / 抓包得到的新结论写进 `refs/FINDINGS.md`，别散落在聊天记录里。
- 一次性的分析脚本可以放在 `refs/`，但产物（解包目录、strings dump）不要提交，见 `.gitignore`。

### 5.1 版本号规则（SemVer）

版本号**直接写在 `android-app/app/build.gradle.kts` 里，不依赖任何分支**。
发布时打一个同名 tag，tag 才是「这份 Release 对应哪份代码」的锚点。

> 早期版本写过一套「从版本分支 `V1.10.1` 推导版本号」的规则（找离 `HEAD` 最近的
> `V*` 分支、并列时取最大……）。**实践下来没必要** ——
> 一个人开发、`main` 上直接改就行，`V1.10.1` 只是 `main` 的一份多余副本，
> 删掉不会丢失任何信息。真要同时维护多条版本线时再加分支不迟。
> 真正干活的是 **tag**：每个版本一个，`git tag` 一看就知道。

**语义化版本（`MAJOR.MINOR.PATCH`）—— 只增不减，一旦发布不再修改：**

| 位置 | 什么时候动 | 例子 |
|---|---|---|
| `MAJOR` | 对外行为或数据格式**不兼容**的改动（老配置 / 老流程会坏掉） | 1.9.0 → 2.0.0 |
| `MINOR` | **向后兼容**地加功能、加选项 | 1.9.0 → 1.10.0 |
| `PATCH` | **向后兼容**地修缺陷，不改接口与行为约定 | 1.9.0 → 1.9.1 |

**映射到 Android（两段必须同源，改一处就要改全部）：**

```kotlin
versionName = "1.10.1"     // 手写
versionCode = 11001        // MAJOR*10000 + MINOR*100 + PATCH
```

`versionCode` 用这个公式是为了让**数字大小和版本新旧一致**（Android 只认单调递增），
`MINOR` / `PATCH` 各留两位：`1.9.0 → 10900`、`1.10.0 → 11000`、`2.0.0 → 20000`。
`MINOR` 是数字不是字符串，`1.10.0` 比 `1.9.0` 新。

**发布时打 tag**（`gh release create` 会自动建，不用手动 `git tag`）：

```bash
gh release create v1.10.1 "jingjing-yuepao-v1.10.1.apk" \
  --title "鲸鲸约跑 v1.10.1" --notes-file notes.md
```

tag 名 = `v` + `versionName`，和 Release 一一对应。

> ⚠️ 附件名**必须是纯英文**：GitHub 会把非 ASCII 字符剥掉，
> `鲸鲸约跑-v1.10.1.apk` 会变成 `-v1.10.1.apk`。
> 而且 `#标签` 语法只设 label，**不改真实文件名**，别指望它。

### 5.2 改版本号时必须同步的位置

| 位置 | 改什么 |
|---|---|
| `android-app/app/build.gradle.kts` | `versionName`（手写）、`versionCode`（公式值） |
| `android-app/app/src/main/AndroidManifest.xml` | `<meta-data android:name="version">` = `versionName` |
| `CHANGELOG.md` | 新增 `## [x.y.z] — 日期 · 摘要` 段落，并在文末补对应的引用式链接 |
| `AGENT.md` §1 表格 | 「当前版本」一行 |
| `README.md` / `android-app/README.md` | 头部「当前版本」标记与版本记录段落 |

**不要**在代码里写死版本号：界面显示一律用 `BuildConfig.VERSION_NAME`
（`MainActivity.kt` 已经这么做），文案里出现的小版本号
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
