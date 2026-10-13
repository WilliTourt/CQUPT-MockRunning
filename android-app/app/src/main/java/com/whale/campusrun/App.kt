/*
 * Application 入口：只干一件事 —— 在任何 Activity 之前把主题定下来。
 *
 * 为什么不能放在 Activity.onCreate 里：
 *   super.onCreate() 会按「跟随系统」解析一次主题（系统是浅色就用浅色），
 *   之后再调 setDefaultNightMode(深色) 会触发 Activity 重建 ——
 *   用户看到的就是「先闪一下白，再变黑」。
 *
 *   放在这里的话，theme 在 Activity 创建之前就已经是深色，一次到位，没有重建。
 *
 * 作者：WilliTourt的蓝色大肥鱼 🐟
 */
package com.whale.campusrun

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(
            getSharedPreferences("campusrun", MODE_PRIVATE)
                .getInt("themeMode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        )
    }
}
