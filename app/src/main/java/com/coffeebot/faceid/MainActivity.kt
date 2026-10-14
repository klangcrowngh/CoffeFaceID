package com.coffeebot.faceid

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.coffeebot.faceid.ui.AdminLogin
import com.coffeebot.faceid.ui.AdminScreen
import com.coffeebot.faceid.ui.KioskScreen

private enum class Screen { KIOSK, LOGIN, ADMIN }

class MainActivity : ComponentActivity() {

    private var hasCamera by mutableStateOf(false)

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCamera) cameraPermission.launch(Manifest.permission.CAMERA)

        val app = application as App
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var screen by androidx.compose.runtime.remember { mutableStateOf(Screen.KIOSK) }
                // «Назад» в режиме киоска ничего не делает; в админке — выход из неё.
                BackHandler { if (screen != Screen.KIOSK) screen = Screen.KIOSK }

                when {
                    !hasCamera -> Column(
                        Modifier.fillMaxSize().background(Color.Black),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Нужен доступ к камере", color = Color.White, fontSize = 28.sp)
                        Button(onClick = { cameraPermission.launch(Manifest.permission.CAMERA) }) { Text("Разрешить") }
                    }
                    screen == Screen.ADMIN -> AdminScreen(
                        app,
                        onExit = { screen = Screen.KIOSK },
                        onCloseApp = {
                            app.events.add("admin", "приложение закрыто из админки")
                            Kiosk.exit(this@MainActivity)
                        },
                        isDeviceOwner = Kiosk.isDeviceOwner(this@MainActivity),
                    )
                    else -> {
                        KioskScreen(app, onAdminRequested = { screen = Screen.LOGIN })
                        if (screen == Screen.LOGIN) {
                            AdminLogin(app, onSuccess = {
                                app.events.add("admin", "вход в админку")
                                screen = Screen.ADMIN
                            }, onCancel = { screen = Screen.KIOSK })
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Kiosk.enter(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
