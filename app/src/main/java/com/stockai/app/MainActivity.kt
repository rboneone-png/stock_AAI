package com.stockai.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.stockai.app.ui.MainViewModel
import com.stockai.app.ui.StockAiApp
import com.stockai.app.work.Notifier

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.notificationsGranted.value = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.notificationsGranted.value = Notifier.canNotify(this)
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(this)) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { StockAiApp(vm, requestNotifications = {
            if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }) }
    }

    override fun onResume() {
        super.onResume()
        vm.notificationsGranted.value = Notifier.canNotify(this)
    }
}
