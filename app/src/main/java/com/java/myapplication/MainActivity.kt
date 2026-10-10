package com.java.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.java.myapplication.data.AppSettings
import com.java.myapplication.data.CookieStore
import com.java.myapplication.engine.AppCtx
import com.java.myapplication.ui.MainScreen
import com.java.myapplication.ui.OnboardingScreen
import com.java.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 启动时把已导入的 cookie 加载进运行时请求头
        CookieStore.loadIntoHeader(this)
        // 启动时把网络设置（超时/重试/弱网）应用到 Http 客户端
        AppSettings.applyToHttp(this)
        // 全局注入 applicationContext（供 WebView 取页等需要 Context 的引擎使用）
        AppCtx.init(this)

        setContent {
            MyApplicationTheme {
                Root()
            }
        }
    }
}

/**
 * 依据是否完成引导，显示引导页或主界面。
 */
@Composable
fun Root() {
    val context = LocalContext.current
    var onboarded by remember { mutableStateOf(AppSettings.onboarded(context)) }

    if (onboarded) {
        MainScreen()
    } else {
        OnboardingScreen(
            onFinish = {
                AppSettings.setOnboarded(context, true)
                onboarded = true
            }
        )
    }
}