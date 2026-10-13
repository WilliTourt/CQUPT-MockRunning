# 鲸鲸约跑

> **作者：WilliTourt的蓝色大肥鱼 🐟** · v1.8

替你跑完这两公里 · 你只管去刷脸。

**不申请定位权限，不连服务器，不需要登录。**

适用于 HarmonyOS 4.0（Android 12 基底）及所有 Android 7.0+ 设备。

---

## 主题

右上角一个小按钮，点一下循环切换：

```
🌗 自动  →  🌙 深色  →  ☀ 浅色  →  🌗 自动
```

选择会记住。实现方式是 AppCompat 的 `DayNight`：

| 资源 | 浅色 | 深色 |
|---|---|---|
| `values/colors.xml` | `#F2F5FA` 底 / `#16202F` 字 | — |
| `values-night/colors.xml` | — | `#0B1220` 底 / `#E8EEF7` 字 |

所有颜色（背景、卡片、描边、强调色、成功/危险色，连按钮上的文字色）都走资源引用，
没有一处写死。切主题时 `AppCompatDelegate` 会重建界面 —— 而跑步线程归单例所有，
所以**切主题不会影响正在进行的打卡**。

### 主题为什么要放在 Application

`AppCompatDelegate.setDefaultNightMode()` 写在 `Activity.onCreate()` 里会**先白闪一下**：

```
Activity.onCreate()
  ├─ super.onCreate()          ← 此时按「跟随系统」解析主题
  │                              系统是浅色就用浅色，窗口已经画出来了
  ├─ setDefaultNightMode(深色)  ← 才发现该用深色 → 触发 Activity 重建
  └─ setContentView()           ← 重建之后才是深色
```

这也解释了为什么**设成浅色就没这个问题** —— 浅色 + 系统浅色不需要改，不触发重建。

放到 `App.onCreate()` 里就没事了：Application 比任何 Activity 都早，
主题在 Activity 创建之前就定好，一次到位，不重建。

（`App` 类已注册在 manifest 的 `<application android:name=".App">`。）

---

## 图标

用的是插画版——蓝色大肥鱼本鱼在操场上跑。源图 1024×1024。

**踩过的坑**：自适应图标的画布是 108×108，但系统**只渲染中间 72×72 那一块**并套蒙版。
我第一版预览时拿整块画布当圆，看起来"有个方框"，其实是预览比真实情况多露了一圈。
第二版又把插画裁到人物特写，构图丢了。

现在的做法：**整张插画缩到 70/108 居中**，图片方块的边落在可见区之外，
所以 bleed 永远看不见，也不会出现接缝；同时整张图完整可见。

| 文件 | 说明 |
|---|---|
| [`art/source.png`](../refs/icon/art/source.png) | 原图 |
| [`make_icon_art.py`](../refs/icon/make_icon_art.py) | 生成脚本，改 `IMG_SCALE` 调取景 |
| [`preview_scale_compare.png`](../refs/icon/preview_scale_compare.png) | 五档取景对比（蒙版按真实比例算） |
| [`preview_adaptive_circle.png`](../refs/icon/preview_adaptive_circle.png) | 圆形蒙版观感 |
| [`preview_sizes.png`](../refs/icon/preview_sizes.png) | 48/72/96/144/192 各尺寸实拍 |

---

## 版本记录

### v1.8
| 改动 | 说明 |
|---|---|
| 🌓 **修掉深色模式启动白闪** | 见下方「主题为什么要放在 Application」 |
| 🎨 **电池提示红/绿底色** | 未加白名单 = 红底，已加 = 绿底，一眼能看出来 |
| 🐛 **授权后即时刷新** | 原来只在 `onCreate` 检测，授权完得重启 App 才变绿。现在 `onResume` 也检测，从系统设置返回就自动变绿 |
| 🐛 **修掉脚本读不到** | 微信运行时会把 HTTP 缓存文件独占打开，普通 `open()` 抛 `PermissionError`，而我原来是 `except OSError: continue` **静默跳过**。现在用 `CreateFileW` + `FILE_SHARE_READ\|WRITE\|DELETE` 绕过（读锁文件不用关微信了） |
| 📎 **脚本可以当文件分享** | 走 FileProvider，发到微信「文件传输助手」后电脑上就是能直接双击的 `get_openid.py`，不用从聊天记录里复制粘贴 |

### v1.7
| 改动 | 说明 |
|---|---|
| 🔠 **整体放大字号** | 全部 +2sp（最小的几档 +2~3sp），固定高度控件跟着长 |

### v1.6
| 改动 | 说明 |
|---|---|
| 🐛 **修掉后台被拖慢** | 见下方「后台保活」 |
| 🔑 **加了「取码」页** | App 内置 openid 提取脚本，可复制 / 分享到电脑；附「openid 是什么」「为什么手机拿不到」的说明 |
| 🎨 **明暗主题** | 自动 / 深色 / 浅色，右上角切换，全资源化配色 |
| 🖼 **图标** | 整张插画不裁不缩，修正自适应蒙版的预览算法 |

---

## 后台保活（v1.6 重点）

**症状**：息屏 / 退出 App 几分钟后，进度条几乎不动，只有「用时」在涨。

**根因**——节拍循环是按"睡了几次"算时间的：

```kotlin
var slept = 0L
while (slept < 8000) {
    Thread.sleep(250)
    slept += 250      // ← 假设每次都正好睡 250ms
}
```

息屏后 CPU 被挂起，`Thread.sleep(250)` 实际可能睡 2 秒，但代码还傻乎乎地
`slept += 250`。所以"8 秒"的一批实际拖了 12 秒以上，**整轮被拖长一倍多**。

**三处修复**：

| 修复 | 做法 |
|---|---|
| 1. 按真实时间计节拍 | 改用 `SystemClock.elapsedRealtime()` 定目标时刻，睡过头就立刻跳过剩余等待 |
| 2. CPU 唤醒锁 | `RunService` 里持一把 `PARTIAL_WAKE_LOCK`，息屏后 CPU 不睡 |
| 3. 电池优化白名单 | 界面里有状态条，未加入时会引导跳系统设置。**深度 Doze 会连唤醒锁一起忽略，只有进白名单才彻底** |

第 3 点是关键：Android 的 Doze 模式下，系统会**忽略 wake lock、挂起网络**，
前台服务只能保证进程不被回收，保不住 CPU。所以第一次用的时候，
点一下那条黄色的提示，把 App 加进白名单（只需设置一次）。

另外日志现在每 10 批打一行，能看出它确实在动：

```
批次 10/99　已跑 0.20/2.00 km　用时 01:20
批次 20/99　已跑 0.40/2.00 km　用时 02:41
```

### v1.6 之前的修复
| 改动 | 说明 |
|---|---|
| 🐛 **修掉旧记录接续** | 服务端 `start2` 会把上次没结束的记录原样还回来。现在检测到残留先 `end` 掉再开干净的 |
| 🐛 **修掉按钮不复位** | `running=false` 在最后一次状态广播之后才设，界面永远停在「结束打卡」 |
| 🐛 **修掉切页下滑** | `fullScroll()` 内部会 `requestFocus`，焦点落到日志框上外层就滚过去 |

### v1.3
| 改动 | 说明 |
|---|---|
| 🏗 **架构级修复** | 跑步线程从 Activity 搬到**单例 + 前台服务**。退出/锁屏/切走都照跑，回来**完整恢复** |
| 🔔 **常驻通知** | 通知栏显示实时进度 |
| 🎨 **分段标签栏** | 「打卡 / 统计」改成真正的页面栏样式 |
| 🔀 **按钮合并** | 开始/结束变成同一个按钮换脸 |
| 🎛 **实时模式写死** | 服务端按墙钟算配速，关掉必判无效 |

### 关于「实时模式」

服务端的配速 = **「点开始到点结束的真实耗时」÷ 距离**。所以上传必须按真实节奏走，
整个过程会持续「距离 ÷ 配速」那么久。2 公里 5'45" 约 11.5 分钟 —— 正好够你在操场
挨个摄像头刷脸。

**这段时间你可以随便退出 App**：有前台服务守着，进程不会被系统回收。

---

## 它做什么

```
你填一次 openid
      ↓
点【连接】→ 拉取跑步区域（服务器上的多边形）和配速规则
      ↓
选区域、填配速、填距离
      ↓
点【开始打卡】
      ↓
App 自动：换 token → 生成轨迹 → start2 → 分批上传 → end
      ↓
这段时间你只需要在操场上到处走，把该刷的人脸刷了
```

**全程不碰 GPS。** 所以：

- 不需要开定位
- 不需要 root
- 不需要装 Xposed / VirtualXposed
- 不需要虚拟机、不需要改机

---

## 为什么不需要 GPS

`重邮智慧体育` 是纯客户端信任架构。轨迹点由小程序采集后直接 POST 给服务器，
服务器只负责收点和算总距离 —— 它没有第二路数据源去核对你的点是不是真的。

逆向细节见 [`../refs/FINDINGS.md`](../refs/FINDINGS.md)。

---

## 构建 —— ✅ 已经帮你编好了

**APK 在这里，直接拿走装：**

```
campusrun\鲸鲸约跑-v1.8.apk      5.0 MB
```

署名已经打进包里了——打开 App 界面底部和启动日志第一行都能看到
**「鲸鲸约跑 v1.7 · 由 WilliTourt的蓝色大肥鱼 🐟 编写」**。

签名已验证（APK Signature Scheme v2，Android Debug key）。手机上打开时如果提示
「未知来源」，选允许本次安装即可。

### 想自己重新编？（可选）

我这台机器上已经把工具链装好了，全部是绿色解压版，不写注册表：

| 组件 | 位置 |
|---|---|
| JDK 17 (Temurin 17.0.20.1) | `E:\AndroidDev\jdk17\` |
| Android SDK (platform 34 / build-tools 34.0.0 / platform-tools) | `E:\AndroidDev\sdk\` |
| Gradle 8.7 | `E:\AndroidDev\gradle-8.7\` |
| Gradle 编译缓存 | `%USERPROFILE%\.gradle\` |

合计约 2.2 GB。不想要了直接 `rmdir /s E:\AndroidDev` 删干净。

> 上面这些是**开发机上的实际摆放**（都塞在 `E:\AndroidDev` 下，不进注册表、不改 PATH）。
> 你按自己习惯的位置放就行，只要 `JAVA_HOME` 和 `ANDROID_HOME` 指对、Gradle 能找到 SDK 就成。

重新编译一条命令：

```powershell
$env:JAVA_HOME  = 'E:\AndroidDev\jdk17'
$env:ANDROID_HOME = 'E:\AndroidDev\sdk'
E:\AndroidDev\gradle-8.7\bin\gradle.bat `
  -p <仓库根目录>\android-app `
  assembleRelease
```

> 仓库里带了 **gradle wrapper**，所以也可以不用系统 Gradle：
> ```powershell
> cd android-app
> .\gradlew.bat assembleRelease
> ```
> wrapper 第一次跑会自动下载 Gradle 8.7 到 `%USERPROFILE%\.gradle`。

产物在 `android-app\app\build\outputs\apk\release\app-release.apk`。

跑单元测试（验证配速标定和围栏判定）：

```powershell
E:\AndroidDev\gradle-8.7\bin\gradle.bat -p <上面那个路径> :app:testDebugUnitTest
```

### 如果不用我给你装的工具链

装 [Android Studio](https://developer.android.com/studio) 也行，自带 JDK 和 SDK，
`File → Open` 选 `android-app/` 目录，等 Gradle Sync 完，`Build → Build APK(s)`。

---

## 使用

### 第一步：拿 openid（只需一次）

openid 是你在这个小程序里的身份标识。**不需要抓包，本地一条命令搞定**：

```bash
cd ..                              # 回到 campusrun 目录
python get_openid.py
```

输出：

```
找到 1 个身份：

  [1] openid    : oXXXXXXXXXXXXXXXXXXXXXXXXXXX
      尾号      : ****XXXX
      统一认证码: 1234567
      学号      : 20XXXXXXXX
      来源      : webview_<hash>/data_1
```

**原理**：电脑版微信 4.x 把小程序跑在 Chromium 里，`checkBinding` 的响应体
明文缓存在 `radium\web\profiles\webview_<hash>\Cache\Cache_Data\`。
不解密、不联网、不碰 MMKV。

前提是**这台电脑的微信打开过一次「重邮智慧体育」并完成绑定**。
没做过的话，先在电脑微信里打开小程序走一遍绑定流程。

> 那个商业采集器读的是同一份缓存，区别是它把你的身份传到 `cyschoolrun.top`。
> 这个脚本只在本地读出来给你。

填进 App 后会被保存，以后不用再弄。

### 第二步：连接

点【① 连接并拉取场地】。日志里会打印：

```
① 换取 token ...
   ✓ token = oABC****WXYZ
② 拉取跑步规则 ...
   配速区间 4'00" ~ 7'30"/km，建议 5'45"
   达标里程 2000 m
③ 拉取区域多边形 ...
   共 3 个可跑步场地
     · 风华运动场 / T1001 / 围栏 20 顶点 / 禁跑区 1 块
     · 太极运动场 / T1005 / 围栏 5 顶点 / 禁跑区 1 块
     · 宁静苑 / T1014 / 围栏 6 顶点
```

**如果 `isBind=false`**，说明 openid 不对，或者身份还没在小程序里绑定过。

### 第三步：设定配速

学校规则实测是 **4'00" ~ 7'30"/km**，达标里程 **2000 米**。

- **取区间中段，5'45" 左右**。别贴边 —— 填 4'01" 就是自己找事。
- **距离填 2.0**。
- **实时模式建议开着**。2 公里 5'45" 大约 11.5 分钟，这段时间正好够你
  走到各个摄像头前面刷脸。关掉的话所有点几秒钟就传完了。

### 第四步：开始打卡，去走你的脸

点【② 开始打卡】，然后把手机揣兜里，去操场。

**App 需要保持在前台**（我加了 keepScreenOn，不会息屏）。别划掉它。

日志会实时刷新进度：

```
④ 生成轨迹（区域：风华运动场）
   点数 690 | 距离 2000.0 m | 时长 689s | 实测配速 5'44"/km
   自检：围栏内 690/690 = 100.00% | 禁跑区 0 点
⑤ 开始跑步 start2 ...
   ✓ sportRecordNo = 1234567_T1001_20261013181234
⑥ 上传轨迹点 ...
   共 690 点 → 99 批（每批 8，末点作下批首点）
   批次 1/99   ✓
   ...
⑦ 结束跑步 end ...
   ✓ code=10200 请求成功
完成。共耗时 692 秒
```

---

## 人脸打卡

**这部分我帮不了你，得你本人去。**

小程序里根本不调相机。它在 `jpq` 类型的区域里只做一件事：`wx.vibrateShort` 连震十下，
提醒你"该去扫脸了"。真正的人脸比对在操场上的物理摄像头里。

那份《重邮约跑》的商业教程里写得也一样：「人脸检测需要你本人在摄像头下正常完成」。

所以配合方式是：**App 在后台（其实是前台）跑轨迹，你人在操场上走，挨个摄像头刷。**

---

## 常见问题

**Q：为什么不需要定位权限？**
A：因为根本不读 GPS。轨迹是本地算出来的，直接 POST 出去。

**Q：会被检测到是在伪造吗？**
A：服务端能看到的只有你 POST 的点。App 做了三件事让它看起来正常：
配速精确匹配（不是恒定值）、航向连续变化（不是几个固定方向）、
GPS 噪声符合真实手机的特征（AR(1) 相关噪声）。

**Q：日志里 `isBind=false` 怎么办？**
A：openid 不对。重新抓一次，注意别把 `openid=` 后面的 `&` 一起复制进去。

**Q：token 过期了怎么办？**
A：不用管。每次点【开始打卡】都会重新换取 token。

**Q：能改跑步区域吗？**
A：区域是服务器下发的，App 只负责从里面挑一个生成闭环。
跑一次【连接】就能看到你这学期有几个可用区域。

**Q：中间断了怎么办？**
A：重来。但注意别在跑步过程中手动停止 —— 小程序那边手动停止会导致这次作废。

---

## 源码结构

```
android-app/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml           只申请 INTERNET 权限
        ├── java/com/whale/campusrun/
        │   ├── TrackGen.kt               Geo 几何 + 轨迹生成（配速标定、有机化、噪声）
        │   ├── Api.kt                    协议客户端（HttpURLConnection，零第三方依赖）
        │   ├── RunEngine.kt              流程编排：token → 区域 → start2 → 上传 → end
        │   └── MainActivity.kt           界面
        └── res/
            ├── layout/activity_main.xml
            ├── drawable/{box,btn_ghost,btn_solid}.xml
            └── values/{colors,strings,themes}.xml
```

依赖只有三个：`appcompat`、`core-ktx`、`material`。网络层手写 `HttpURLConnection`，
JSON 用系统自带的 `org.json` —— 没有 OkHttp、没有 Retrofit、没有 Gson。
这样工程好维护，也不会因为依赖版本问题编不过。

---

## 算法验证

`TrackGen.kt` 有 **7 个单元测试**，跑在 JVM 上，输入是**从服务器真实抓下来的
三个场地多边形**（含真实的 jpq 禁跑区）。全部通过：

```
套件 com.whale.campusrun.TrackGenTest   用例 7  失败 0  错误 0  耗时 0.42s
   [PASS] 风华运动场 各配速都精确命中
   [PASS] 所有点必须落在电子围栏内
   [PASS] 不能有任何点落在禁跑区
   [PASS] 太极运动场与宁静苑同样达标
   [PASS] 轨迹形态要像人跑的，不是机器画的
   [PASS] 时间轴必须逐秒递增
   [PASS] 点在多边形内的判定与射线法一致
```

断言的三条硬指标（4'00" / 5'30" / 5'45" / 7'00" 四档）：

| 指标 | 要求 | 实测 |
|---|---|---|
| 实测配速 vs 目标配速 | 误差 ≤ 1 秒 | ✅ |
| 点在电子围栏内 | 100% | ✅ 690/690 |
| 点在禁跑区 | 0 个 | ✅ 0 |

另外还断言了「像人跑的」：航向取值 > 40 个不同值（矩形只有 4 个）、
速度有波动、逐点间距有抖动、精度字段在 3~20 m。

跑测试：

```powershell
$env:JAVA_HOME='E:\AndroidDev\jdk17'
E:\AndroidDev\gradle-8.7\bin\gradle.bat -p . :app:testDebugUnitTest
```

报告在 `app\build\reports\tests\testDebugUnitTest\index.html`。

> 之前用 Python 镜像验证过一遍（[`../refs/verify_real_areas.py`](../refs/verify_real_areas.py)），
> 现在是**直接跑编译进 APK 的那份 Kotlin 代码**，不是镜像。
