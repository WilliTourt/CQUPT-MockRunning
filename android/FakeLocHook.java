package com.whale.campusrun;

/*
 * campusrun / android / FakeLocHook.java
 *
 * LSPosed 模块：在微信进程（com.tencent.mm）内直接接管 android.location.LocationManager，
 * 按 track.json 的时序把伪造轨迹喂给所有 LocationListener。
 *
 * 隐蔽性要点：
 *   1. 完全进程内，不经过 mock provider，没有 isFromMockProvider 可用
 *   2. 同时 hook isFromMockProvider() / isMock() 双保险
 *   3. 拦截 requestLocationUpdates，真实 provider 根本不会上报
 *   4. 轨迹走 assets/track.json，由模块 APK 自带（避免 /sdcard 权限问题）
 *
 * 编译：Android Studio 建 Empty Module (no activity) -> 加 LSPosed api
 *       implementation 'io.github.libxposed:api:100' 或旧版 de.robv.android.xposed:api:82
 *       把 track_engine.py 生成的 track.json 放进 src/main/assets/
 *
 * 作用域：只勾 com.tencent.mm（微信）
 */

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class FakeLocHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TAG = "CampusRun";
    private static final String TARGET_PKG = "com.tencent.mm";
    private static String sModulePath = null;

    @Override
    public void initZygote(StartupParam startupParam) {
        sModulePath = startupParam.modulePath;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET_PKG.equals(lp.packageName)) return;

        XposedBridge.log(TAG + ": 装载到 " + lp.packageName);
        TrackEngine.load(lp);

        hookLocationClass(lp);
        hookLocationManager(lp);
        hookTencentSdk(lp);
    }

    /* ---------------- 1. 抹掉"模拟位置"标记 ---------------- */

    private void hookLocationClass(XC_LoadPackage.LoadPackageParam lp) {
        final XC_MethodReplacement falseRep = new XC_MethodReplacement() {
            @Override protected Object replaceHookedMethod(MethodHookParam p) {
                return Boolean.FALSE;
            }
        };

        // Android 12+ : Location.isMock()
        safeHook(() -> XposedHelpers.findAndHookMethod(
                Location.class, "isMock", falseRep));

        // Android 6~11 : Location.isFromMockProvider()
        safeHook(() -> XposedHelpers.findAndHookMethod(
                Location.class, "isFromMockProvider", falseRep));

        // 部分 SDK 读字段而不是方法
        safeHook(() -> XposedHelpers.findAndHookMethod(
                Location.class, "setMock", boolean.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        p.args[0] = Boolean.FALSE;
                    }
                }));
    }

    /* ---------------- 2. 接管 LocationManager ---------------- */

    private void hookLocationManager(XC_LoadPackage.LoadPackageParam lp) {
        final Class<?> LM = LocationManager.class;

        // 2.1 持续定位：劫持请求，注册我们自己的 feeder
        for (String m : new String[]{"requestLocationUpdates", "requestLocationUpdatesAsUser"}) {
            safeHook(() -> XposedHelpers.findAndHookMethod(
                    LM, m,
                    String.class, long.class, float.class, LocationListener.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            String provider = (String) p.args[0];
                            long minTime = (Long) p.args[1];
                            LocationListener l = (LocationListener) p.args[3];
                            if (l != null) {
                                TrackEngine.feed(provider, Math.max(minTime, 1000L), l);
                            }
                            p.setResult(null);   // 屏蔽真实注册
                        }
                    }));
        }

        // 2.2 一次性定位
        safeHook(() -> XposedHelpers.findAndHookMethod(
                LM, "getLastKnownLocation", String.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        String provider = (String) p.args[0];
                        p.setResult(TrackEngine.makeLocation(provider));
                    }
                }));

        // 2.3 旧式请求
        safeHook(() -> XposedHelpers.findAndHookMethod(
                LM, "requestSingleUpdate",
                String.class, LocationListener.class, Looper.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        String provider = (String) p.args[0];
                        LocationListener l = (LocationListener) p.args[1];
                        if (l != null) TrackEngine.feed(provider, 1000L, l);
                        p.setResult(null);
                    }
                }));
    }

    /* ---------------- 3. 腾讯定位 SDK 兜底 ---------------- */

    private void hookTencentSdk(XC_LoadPackage.LoadPackageParam lp) {
        final String[] classes = {
                "com.tencent.map.geolocation.TencentLocation",
                "com.tencent.map.geolocation.TencentLocationManager",
        };
        for (String cn : classes) {
            Class<?> c;
            try {
                c = XposedHelpers.findClass(cn, lp.classLoader);
            } catch (Throwable t) {
                continue;
            }
            safeHook(() -> XposedHelpers.findAndHookMethod(
                    c, "isMockGps", new XC_MethodReplacement() {
                        @Override protected Object replaceHookedMethod(MethodHookParam p) {
                            return Boolean.FALSE;
                        }
                    }));
        }
    }

    private interface ThrowingRunnable { void run() throws Throwable; }

    private static void safeHook(ThrowingRunnable r) {
        try { r.run(); }
        catch (Throwable t) { XposedBridge.log(TAG + ": hook 跳过 - " + t); }
    }

    /* ================================================================
     *  轨迹引擎：读 assets/track.json，按时间轴吐点
     * ================================================================ */

    static final class TrackEngine {

        private static final List<Pt> TRACK = new ArrayList<>();
        private static final Handler H = new Handler(Looper.getMainLooper());
        private static long t0 = 0L;
        private static int idx = 0;
        private static boolean running = false;

        static final class Pt {
            double lat, lon, speed, bearing, accuracy, altitude;
            Pt(double lat, double lon, double speed, double bearing,
               double accuracy, double altitude) {
                this.lat = lat; this.lon = lon; this.speed = speed;
                this.bearing = bearing; this.accuracy = accuracy; this.altitude = altitude;
            }
        }

        static void load(XC_LoadPackage.LoadPackageParam lp) {
            if (sModulePath == null) { XposedBridge.log(TAG + ": modulePath 为空"); return; }
            try (ZipFile zf = new ZipFile(sModulePath)) {
                ZipEntry e = zf.getEntry("assets/track.json");
                if (e == null) { XposedBridge.log(TAG + ": 找不到 assets/track.json"); return; }
                InputStream in = zf.getInputStream(e);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();

                JSONObject root = new JSONObject(bos.toString("UTF-8"));
                JSONArray arr = root.getJSONArray("points");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    TRACK.add(new Pt(
                            o.getDouble("lat"), o.getDouble("lon"),
                            o.optDouble("speed", 3.0), o.optDouble("bearing", 0),
                            o.optDouble("accuracy", 8.0), o.optDouble("altitude", 260.0)));
                }
                XposedBridge.log(TAG + ": 载入轨迹点 " + TRACK.size());
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": 载入失败 " + t);
            }
        }

        /** 构造一个 Location，并补齐 Android 12+ 才有的精度字段。 */
        static Location makeLocation(String provider) {
            Location loc = new Location(provider == null ? "gps" : provider);
            Pt p;
            synchronized (TRACK) {
                if (TRACK.isEmpty()) return loc;
                p = TRACK.get(Math.min(idx, TRACK.size() - 1));
            }
            loc.setLatitude(p.lat);
            loc.setLongitude(p.lon);
            loc.setAltitude(p.altitude);
            loc.setAccuracy((float) p.accuracy);
            loc.setSpeed((float) p.speed);
            loc.setBearing((float) p.bearing);
            loc.setTime(System.currentTimeMillis());
            try {
                XposedHelpers.callMethod(loc, "setElapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos());
            } catch (Throwable ignored) { }

            // 隐藏精度字段，避免 SDK 因缺字段丢弃点位
            setFloat(loc, "mHorizontalAccuracyMeters", (float) p.accuracy);
            setFloat(loc, "mVerticalAccuracyMeters", (float) (p.accuracy * 1.5));
            setFloat(loc, "mSpeedAccuracyMetersPerSecond", 0.8f);
            setFloat(loc, "mBearingAccuracyDegrees", 5.0f);
            try { loc.setMock(false); } catch (Throwable ignored) { }
            return loc;
        }

        private static void setFloat(Object o, String field, float v) {
            try { XposedHelpers.setFloatField(o, field, v); } catch (Throwable ignored) { }
        }

        /** 为某个 listener 启动独立的时间轴推进。 */
        static synchronized void feed(final String provider, final long minTime,
                                      final LocationListener listener) {
            final long stepMs = Math.max(minTime, 1000L);
            if (t0 == 0L) {
                t0 = System.currentTimeMillis();
                idx = 0;
                running = true;
            }
            XposedBridge.log(TAG + ": 开始喂点 provider=" + provider + " step=" + stepMs + "ms");

            // 初始状态回调，很多 SDK 靠这个判定 provider 可用
            try {
                listener.onStatusChanged(provider, Bundle.EMPTY);
                listener.onProviderEnabled(provider);
            } catch (Throwable ignored) { }

            H.post(new Runnable() {
                @Override public void run() {
                    if (!TRACK.isEmpty() && idx < TRACK.size()) {
                        try { listener.onLocationChanged(makeLocation(provider)); }
                        catch (Throwable t) { XposedBridge.log(TAG + ": callback 异常 " + t); }
                        idx++;
                        H.postDelayed(this, stepMs);
                    } else {
                        running = false;
                        XposedBridge.log(TAG + ": 轨迹播放完毕");
                    }
                }
            });
        }
    }
}
