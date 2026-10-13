# 重邮智慧体育 · 小程序逆向报告

> 样本：微信 PC 4.0 小程序缓存 `__APP__.wxapkg`（506,410 字节，`V1MMWX` 加密）
> 解包后 89 个文件 / 812,797 字节，主逻辑 `app-service.js` 151KB
> 包内自报域名 `sport.cqupt.edu.cn` → **确认目标就是重邮智慧体育**

---

## 0. 结论速览

| 项 | 结论 |
|---|---|
| 请求签名 | **无**。全包 md5/AES/RSA/CryptoJS 关键词 0 命中，只有 2 处 `publicKey` 声明（空串，未使用） |
| 请求加密 | **无**。全部明文 JSON，明文 URL |
| 认证方式 | `header: {token}`，token 由 `checkBinding` 下发 |
| token 获取 | **只要有 `openid` 就能换**，`wxCode` 可传空串 |
| 轨迹数据 | 明文点列，`isValid` 字段**由客户端自己算**，服务端照单全收 |
| 判定逻辑 | 瞬移检测 / 区域检测 / 补点 全在客户端 JS 里；服务端只根据收到的点算距离 |
| 人脸 | **场地摄像头扫脸，小程序不参与**。`jpq` 类型区域进入时仅触发震动提醒 |
| 破解难度 | 极低。整条链路是"客户端说了算" |

**核心结论：这是纯客户端信任架构。服务端把 `isValid`、区域判定、补点全交给前端，
后端只做"收点 → 算总距离"。直接构造请求重放即可，不需要 GPS 伪造，也不需要签名逆向。**

---

## 1. 包体解密（V1MMWX）

微信 PC 4.x 的 `__APP__.wxapkg` 是新格式：

```
[0:6]      b'V1MMWX'
[6:1030]   1024 字节，AES-CBC 加密的原始 wxapkg 头
[1031:]    其余数据，单字节 XOR
key      = PBKDF2-HMAC-SHA1(password = appid, salt = b"saltiest", iters = 1000, dkLen = 32)
iv       = b"the iv: 16 bytes"
xor_key  = ord(appid[-2])          # appid = wx13b7520eb7351f4f → ord('4') = 0x34
```

解密后是标准 wxapkg，但**头部布局与老版 17 字节头不同**：

```
[0:14]      头部
[5:9]       u32 BE  索引区长度
[9:13]      u32 BE  数据区长度
[14:18]     u32 BE  文件数
[18:...]    条目表：u32 BE 名长 + 名 + u32 BE 偏移 + u32 BE 大小
校验式：14 + 索引长度 + 数据长度 == 文件总长
```

实现见 `wxapkg_unpack.py`（纯 Python AES，带 FIPS-197 自检）。

---

## 2. 接口全表

Base: `https://sport.cqupt.edu.cn/new_wxapp`
全部请求带 `header: { token }`（`checkBinding` 除外）

### 2.1 认证

| 方法 | 路径 | 参数 | 说明 |
|---|---|---|---|
| GET | `/wxUnifyId/checkBinding` | `wxCode`, `openid`, `phoneType` | **换 token**。`isBind=="true"` 时返回 `token` |
| GET | `/wxUnifyId/wecatBindUnifyId` | `unifyId`, `studentNo`, `wxCode` | 首次绑定，返回 `{token, openid}` |
| PUT | `/wxUnifyId/binding/unlock` | | 解绑 |
| GET | `/wxUnifyId/getUser` | | |
| GET | `/wxUnifyId/getUserInfo` | `unifyId`, `studentNo`, `bindType` | |
| GET | `/wxUnifyId/faceImg` | | 人脸图 URL |

**关键：`checkBinding` 的 `wxCode` 可以传空串。** 源码里的 token 刷新分支：

```js
function handleRefreshToken() {
  var openid = wx.getStorageSync("openid");
  return new Promise(
    openid != null && openid !== ""
      ? (res, rej) => doCheckBinding(res, rej, "")          // ← wxCode = ""
      : (res, rej) => wx.login({ success: r => doCheckBinding(res, rej, r.code) })
  );
}
function doCheckBinding(res, rej, wxCode) {
  http({ url: "/wxUnifyId/checkBinding", method: "get",
         data: { wxCode: wxCode, openid: wx.getStorageSync("openid") || "",
                 phoneType: globalData.phoneInfo.model + "_" + globalData.phoneInfo.system } })
}
```

**所以唯一必需的密钥是 `openid`。** 拿到 openid = 永久换 token 的能力。

### 2.2 跑步

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/setting/getRule` | 实测返回 `{raceMile:2000.0, minPace:4.0, maxPace:7.5}` |
| GET | `/place/getPlayground` | 场地列表（14 个） |
| GET | `/area/getAllArea` | **⭐ 区域多边形唯一有效来源** |
| GET | `/areaFunc/getFunc` | 功能字典：`dkq`=打卡区 `dzwl`=电子围栏 `jpq`=禁跑区 |
| GET | `/areaModel/listAll/{x}` | **实测返回空对象 `{}`，不可用** |
| GET | `/sportPoint/listAll/{placeCode}` | 实测返回 `[]` |
| POST | `/sportRecord/sport/start2` | **开始跑步**，见 2.2.1 |
| GET | `/sportRecord/sport/continue/{no}` | 继续 |
| POST | `/sportRecord/sport/end/{no}` | **结束跑步**，无 body |
| GET | `/sportRecord/info/{no}` | 详情，返回 `{mileage, timeConsuming, points}` |
| GET | `/sportRecord/mobile/list/{pageNo}/{pageSize}` | 历史记录 |
| POST | `/sportRecord/point/saveListByNo` | **上传轨迹点（主力）**，timeout 10s |
| POST | `/sportRecord/point/uploadPoints` | 补传异常点 |
| POST | `/sportRecord/point/uploadPointsJson` | 同上 |
| GET | `/sportsResult/list` / `/yearTerm/list` / `/notice/getNotice` / `/promptMessage/getAll` | 杂项 |
| PUT | `/wxUnifyId/binding/unlock` | 解绑 |

### 2.2.1 start2 的确切请求体（`chunk_12.appservice.js`）

跑步页 `pages/sport/sport` 的 `onLoad` 从页面参数里取 `sportRecordNo`，
说明 **start2 是在进入跑步页之前调的**。真正的调用点是那个 5 秒倒计时页面：

```js
Page({
  data: { timeCount: 5, timer: 0 },
  onLoad: async function (a) {              // a = { placeCode, placeName }
    var u = { placeName: a.placeName, placeCode: a.placeCode };   // ← body 只有这两个字段
    startSport(u).then(function (e) {
      var t = 5;
      n.data.timer = setInterval(function () {
        if (1 === t) {
          clearInterval(n.data.timer);
          wx.redirectTo({ url: "/pages/sport/sport?sportRecordNo=".concat(e.data)
                              + "&placeCode=".concat(a.placeCode)
                              + "&placeName=".concat(a.placeName) });
        }
        t--; n.setData({ timeCount: t });
      }, 1000);
    }).catch(function (e) { showModal("开启锻炼失败: 未获取到锻炼编号", n.goToHome); });
  }
});
```

```
POST /new_wxapp/sportRecord/sport/start2
body: {"placeName": "中心操场", "placeCode": "1"}
resp: {"code":"10200","msg":"请求成功","data":"<sportRecordNo>"}     ← data 直接是编号字符串
```

**没有 `sportType`、没有经纬度、没有时间戳。** 只有 `placeName` + `placeCode`。

### 2.3 返回码

| code | 含义 |
|---|---|
| `10200` | 业务成功（`http` 包装器只在 `10200` 时 resolve） |
| `40401` / `40402` | token 失效 → 触发 `handleRefreshToken` 自动重登 |
| 其它 | reject，走 `.catch`，点数据落到 `wx.setStorageSync(sportRecordNo)` 本地暂存 |

---

## 3. 关键常量（`app.globalData`）

```js
url:            "https://sport.cqupt.edu.cn/new_wxapp"
faceImgUrl:     "https://sport.cqupt.edu.cn/new_wxapp/wxUnifyId/faceImg"
socketUrl:      "wss://41499f39k5.zicp.fun:42872/ws/sport/start"   // 声明了，但 wx.connectSocket 0 处调用
imgurl:         "https://sport.cqupt.edu.cn"
pointCount:     8        // 每 8 个点上传一批
notInPlayCount: 20       // 连续 20 次不在区域内 → 弹窗终止
timeout:        60000
scale:          15
funcode:        "dzwl"   // 打卡区
daqfuncode:     "daq"
jpqfuncode:     "jpq"    // 人脸识别区（进入触发震动）
role:           -1
publicKey:      ""       // 声明未用
```

---

## 4. 轨迹点协议

### 4.1 上传体

```json
POST /new_wxapp/sportRecord/point/saveListByNo
{
  "sportRecordNo": "本次跑步记录号",
  "sportPointList": [
    {
      "sportRecordNo": "同上",
      "longitude": 106.60790879991319,
      "latitude": 29.533416883680555,
      "placeName": "区域名",
      "placeCode": "区域码",
      "collectTime": "2026-10-13 13:22:10",
      "isValid": "1"
    }
  ]
}
```

`isValid` 取值：`"1"` = 正常，`"2"` = 在 `jpq`（人脸区）内。
**这个字段完全由客户端填。** 源码：

```js
var i = o.isInPoints(t.latitude, t.longitude);
if (i === 0) { 不在区域内，notInPlay++ }
else if (i === 3) { d = "2"; }                       // jpq 区
else { d = "1"; s.length > 0 && o.fillPoint(a); }

var c = {
  sportRecordNo: o.data.sportRecordNo,
  longitude: t.longitude, latitude: t.latitude,
  placeName: o.data.placeName, placeCode: o.data.placeCode,
  collectTime: getTime(), isValid: d
};
r.push(c);
if (r.length >= globalData.pointCount) {       // 满 8 个点
  o.uploadPointList(r);
  var u = r[r.length - 1]; r = [u];            // 保留最后一个点做下一批的首点
}
```

### 4.2 分批

`pointCount = 8`。每积满 8 点上传一次，**并保留最后 1 点作为下一批的起点**。
也就是说下一批是 [上批末点, p1..p7]，共 8 个。

### 4.3 服务端返回

```js
uploadPoints(a).then(res => {
  calcStoragePoints(res.data);                 // ← 服务端回传权威距离/时间
  res.data.expiredCountInForbiddenArea === 1 → 弹 "oneInForbiddenArea"
  res.data.expiredCountInForbiddenArea === 2 → 弹 "twoInForbiddenArea"
})
```
服务端**回传 `expiredCountInForbiddenArea`**，即禁区超限次数。说明服务端确实在算，
但算的依据是你上传的点，而不是真实 GPS。

---

## 5. 客户端校验（全部可绕）

### 5.1 瞬移检测 `pointIsFly`

```js
pointIsFly: function (t) {
  var e = getDistance(s[s.length-1].latitude, s[s.length-1].longitude, t.latitude, t.longitude);
  return (t.timestamp - s[s.length-1].timestamp) / 6e4 / e < 2;
}
```
`(Δt分钟) / (距离米) < 2` → 判为"飞点"。实际是**卡顿检测**（走得慢/不动才触发），
阈值极松，正常配速根本碰不到。

### 5.2 区域检测 `isInPoints` + `IsPtInPoly`

标准射线法点在多边形内判定，遍历 `modalinput[].areaPointList`：
- `areaFuncCode === "dzwl"` → 返回 1，记录 `placeName`/`placeCode`，停止震动
- `areaFuncCode === "jpq"`  → 返回 3，**触发震动**（提醒你去扫脸）
- 都不在 → 返回 0，`notInPlay++`，累计到 20 就弹窗终止

### 5.3 补点 `fillPoint`

```js
fillPoint: function (t) {
  var e = getDistance(t..., s[last]...);
  var a = t.timestamp - s[last].timestamp;
  if (e >= 20) {                         // 两点间隔 ≥ 20 米才补
    var o = this.getMinDistance(s[last], false);
    var i = this.getMinDistance(t, true);
    if (o === i || o < 0 || i < 0) return;
    if (o < i) { var r = a / (i - o + 2); this.forEachAddPoint(o, i, r); }
    else { ... }
  }
}
```
当两个 GPS 点之间跨了 ≥20 米（说明丢了几个点），会**按区域模型点索引插值补出中间点**，
时间均匀分摊。这是为了轨迹在图上连续。伪造时直接给足密度的点就不会触发。

### 5.4 配速展示

```js
o.list[s].pace = (o.list[s].timeConsuming / 60 / o.list[s].mileage).toFixed(2);
```
配速是**服务端给的 `timeConsuming` / `mileage`** 算的，不是客户端算的。

---

## 6. 人脸识别

- `faceImgUrl = /new_wxapp/wxUnifyId/faceImg`
- 小程序内只有 `wx.previewImage` 预览人脸图（`previewImg`），**没有调用相机做人脸比对**
- `jpq` 类型区域只做 `wx.vibrateShort({type:"heavy"})`，连震 10 次提醒你

**结论：人脸比对在场地物理摄像头上，小程序不参与，也无法从客户端绕过。**
这一点和《重邮约跑》教程里"人脸检测需要你本人在摄像头下正常完成"完全吻合。

---

## 7. 完整可利用链路

```
① 拿到 openid
     来源 A：PC 微信小程序本地存储（radium/.../local/<appid>/usrmmkvstorage*，MMKV 加密）
     来源 B：一次抓包（绑定/刷新接口的响应体里明文带 openid）
     来源 C：/wxUnifyId/wecatBindUnifyId 绑定时返回

② GET  /new_wxapp/wxUnifyId/checkBinding?wxCode=&openid=<OPENID>&phoneType=PC_Windows
        → { isBind: "true", token: "<TOKEN>" }

③ GET  /new_wxapp/setting/getRule                       → maxPace / minPace
   GET  /new_wxapp/place/getPlayground                  → 场地列表
   GET  /new_wxapp/areaModel/listAll/<placeCode>        → 区域多边形
   GET  /new_wxapp/sportPoint/listAll/<placeCode>       → 打卡点

④ POST /new_wxapp/sportRecord/sport/start2              → sportRecordNo

⑤ POST /new_wxapp/sportRecord/point/saveListByNo
        每批 8 点，保留末点作下批首点，collectTime 用 "yyyy-MM-dd HH:mm:ss"

⑥ POST /new_wxapp/sportRecord/sport/end/<sportRecordNo>
```

第 ①② 步是唯一的外部依赖。拿到 `openid` 之后，整条链路是纯 HTTP，可在任何地方跑。

---

## 8. 本机微信缓存现状

```
%APPDATA%\Tencent\xwechat\radium\users\8bb8ce685e51153e793e50a110a0ee27\
  applet\packages\wx13b7520eb7351f4f\1\__APP__.wxapkg    506,410 B   已解密解包 ✅
  applet\local\wx13b7520eb7351f4f\usrmmkvstorage0\...     4,096 B    MMKV 加密（实测 767 字节）
  applet\local\wx13b7520eb7351f4f\usrmmkvstorage1\...     4,096 B    MMKV 加密（实测 642 字节）
  applet\local\wx13b7520eb7351f4f\usr\miniprogramLog\log1 16,779 B   明文日志 ✅
  applet\data\8\applet.db                                4,212 KB   SQLCipher 加密
```

`miniprogramLog/log1` 是明文，直接暴露了 API 调用时序：

```
13:22:10 wx.getStorageSync / wx.setStorageSync / wx.login
13:22:10 App onLaunch → page pages/index/index onLoad
13:22:10 wx.systemInfoSync / wx.login success
13:22:10 wx.startLocationUpdateBackground          ← 进入定位
13:22:11 wx.request → success (seq 0)               ← checkBinding
13:22:11 wx.request → success (seq 0)
```

MMKV 存储（767 + 642 字节）里应当就是 `openid` / `token` / `logs` / `scale`，
但被微信按 appid 派生的密钥加密了。**这一步是当前唯一的堵点。**

---

## 9. 与《重邮约跑》商业方案的对照

| 环节 | 商业方案 | 本报告方案 |
|---|---|---|
| 身份获取 | 采集器.exe 读 PC 微信缓存 → 传云端 | 同一思路：读 `openid`，可本地完成 |
| 运行位置 | 云端服务器代跑 | 本地或云端均可（纯 HTTP） |
| GPS 伪造 | **不需要** | **不需要** |
| 签名逆向 | **不需要**（本来就没签名） | **不需要** |
| 人脸 | 本人到场扫脸 | 同 |
| 收费点 | 按次卖注册码/兑换令牌 | — |

**验证了：我以为要做的 iOS/Android 定位注入层是多余的。这个系统根本不看真实 GPS。**

---

# 附：实机验证与《重邮约跑》采集器分析

## 10. 实测结果（用真实账号跑通）

```
GET /wxUnifyId/checkBinding?wxCode=&openid=<OPENID>&phoneType=PC_Windows
← {"code":"10200","data":{"isBind":"true","token":"9yMLR2c_..."}}     ✓ token 到手

GET /setting/getRule
← {"code":"10200","data":{"raceMile":2000.0,"minPace":4.0,"maxPace":7.5}}
   配速必须落在 4'00" ~ 7'30"/km，里程 2000 m

GET /area/getAllArea
← 8 条记录，其中 3 个可跑步场地（sportsItem="0"）：
    T1001 风华运动场   20 顶点   禁跑区 1 块
    T1005 太极运动场    5 顶点   禁跑区 1 块
    T1014 宁静苑        6 顶点
    （00001 上海成 / 00002 石桥铺 是测试数据）

GET /sportRecord/mobile/list/1/3
← 31 条历史。sportRecordNo 格式：<unifyId>_<placeCode>_<yyyyMMddHHmmss>
```

**`isValid` 的真实语义修正**（`/areaFunc/getFunc` 给的字典）：

| code | 含义 | 影响 |
|---|---|---|
| `dkq` | 打卡区 | — |
| `dzwl` | 电子围栏 | 轨迹必须在这个多边形内 |
| `jpq` | **禁跑区** | ~~人脸区~~。`isValid="2"`，`calcDistance` 返回 0，**这些点不计里程** |

`jpq` 不是人脸区，是禁跑区。之前判断错了，实测纠正。
小程序在进入 `jpq` 时 `vibrateShort` 连震，是警告你跑进禁跑区了。

## 11. 采集器逆向（`pctool` v0.1.7）

| 项 | 结果 |
|---|---|
| 语言 | **Go 1.25.0**，x64，静态编译，无加壳 |
| 框架 | **Wails v2**（Go + WebView2，前端 HTML/JS 打进二进制） |
| 模块名 | `pctool`，版本 0.1.7 |
| 构建机 | `F:/cqupt_schoolRun/pctool/`、`E:/Zcode_Runtime/2026-0927-go/` |
| 上报地址 | `https://cyschoolrun.top/v1/cred/claim`（`claim.Do`）<br>`claim.Query` 对应「只查询是否已开通（不采集）」 |
| 传输 | `claim.pinnedTransport`，有 **SPKI pin**（`SPKI pin 不匹配（%s`） |
| 上报字段 | `Openid` / `CredID` / `Detail` / `SeenAt` |

### 关键：它不读 MMKV，也不解密

全包扫描 `mmkv` / `usrmmkvstorage` / `leveldb` / `indexeddb` / `applet.db` —— **0 命中**。

它读的是 **Chromium 的 HTTP 响应缓存**：

```
%APPDATA%\Tencent\xwechat\radium\web\profiles\webview_<hash>\Cache\Cache_Data\
      data_1     ← checkBinding 的完整响应体，明文 JSON
      data_3     ← 历史记录列表，明文 JSON
```

路径组件 `APPDATA` / `Roaming` / `Tencent` / `xwechat` 是分开的 Go 字符串字面量，
在字符串池里连续存放。这就是它报错文案里说的「**profiles 目录为空**」。

**必须完全退出微信的原因**：Chromium 一直占着这些缓存文件，不退出读不到最新内容。

### 尾号从哪来

UI 上显示的 `****pFR4` 就是 **openid 的最后 4 位**。
本机实测：`oXXXXXXXXXXXXXXXXXXXXXXXXXXX` → `****XXXX`，格式一致。

## 12. 本机零解密提取 openid（完整复现）

不需要抓包，不需要 MMKV 解密，不用联网：

```
%APPDATA%\Tencent\xwechat\radium\web\profiles\webview_<hash>\Cache\Cache_Data\data_1
  ↓  正则  "openid"\s*:\s*"([A-Za-z0-9_-]{7,64})"
openid    = oXXXXXXXXXXXXXXXXXXXXXXXXXXX
unifyId   = 1234567
studentNo = 20XXXXXXXX
```

工具： [`../get_openid.py`](../get_openid.py)

```bash
python get_openid.py          # 列出所有找到的身份
python get_openid.py --first  # 只输出 openid
```

### 与商业方案对比

| 环节 | 《重邮约跑》 | 本方案 |
|---|---|---|
| 取 openid | 采集器.exe 读同一份 Chromium 缓存 → 传给云端服务器 | `get_openid.py` 本地读 → 直接填进 App |
| 是否需要服务器 | **需要**（云端代跑） | **不需要** |
| 是否需要注册/付费 | 需要（注册码 + 兑换令牌） | 不需要 |
| 运行位置 | 云端服务器 | 手机本地 |
| 证书校验 | 自建 SPKI pin | 用系统 CA 校验官方站点 |
| 人脸 | 本人到场 | 本人到场 |

**本方案把中间商去掉了：同一份缓存、同样的读法，但全部在本地完成。**
