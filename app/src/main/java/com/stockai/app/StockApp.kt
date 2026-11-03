package com.stockai.app

import android.app.Application
import com.stockai.app.data.DataConfig
import com.stockai.app.store.Store
import com.stockai.app.work.Notifier
import com.stockai.app.work.Scheduler

class StockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DataConfig.cacheDir = cacheDir
        val store = Store(this)
        store.applyToData(store.loadSettings())
        Notifier.ensureChannel(this)
        Scheduler.apply(this, store)
    }
}
