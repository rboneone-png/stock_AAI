package com.stockai.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stockai.app.MainActivity
import com.stockai.app.R
import com.stockai.app.data.Repo
import com.stockai.app.store.AlertEvent
import com.stockai.app.store.Store
import java.util.concurrent.TimeUnit

object Notifier {
    const val CHANNEL = "alerts"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "股票警示", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "自選股的評等變化、停損停利、創高創低等主動警示"
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notify(ctx: Context, events: List<AlertEvent>) {
        if (events.isEmpty() || !canNotify(ctx)) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val bySymbol = events.groupBy { it.symbol }
        val title = "股票警示：${bySymbol.size} 檔有新事件"
        val lines = bySymbol.map { (sym, evs) -> "${evs.first().name.ifBlank { sym }}：${evs.joinToString("、") { it.text.take(24) }}" }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(NotificationCompat.InboxStyle().also { st -> lines.take(6).forEach { st.addLine(it) } })
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(1001, n) } catch (_: SecurityException) {}
    }
}

object Scheduler {
    private const val NAME = "stock_monitor"

    fun apply(ctx: Context, store: Store) {
        val s = store.loadSettings()
        val wm = WorkManager.getInstance(ctx)
        if (!s.monitorEnabled) { wm.cancelUniqueWork(NAME); return }
        val req = PeriodicWorkRequestBuilder<MonitorWorker>(maxOf(15, s.intervalMin).toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // UPDATE：設定改變間隔時立即套用，不重置已排定的週期
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

class MonitorWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        val settings = store.loadSettings()
        store.applyToData(settings)
        com.stockai.app.data.DataConfig.cacheDir = applicationContext.cacheDir
        val items = store.loadWatchlist()
        if (items.isEmpty()) return Result.success()
        return try {
            val r = Repo.scan(items, settings, store.loadState())
            // 掃描期間使用者若改動過清單，就不覆寫（避免蓋掉新增/刪除）
            if (store.loadWatchlist().map { it.symbol } == items.map { it.symbol }) store.saveWatchlist(r.items)
            store.saveState(r.state)
            if (r.events.isNotEmpty()) {
                store.appendEvents(r.events)
                Notifier.notify(applicationContext, r.events)
            }
            if (r.analyses.isEmpty()) Result.retry() else Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
