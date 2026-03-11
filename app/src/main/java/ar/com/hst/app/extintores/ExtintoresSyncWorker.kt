package ar.com.hst.app.extintores

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

class ExtintoresSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val repo = ExtintoresRepository(applicationContext)
        val pendientes = repo.contarPendientes()
        if (pendientes == 0) return Result.success()

        Log.d("ExtSyncWorker", "Sincronizando $pendientes controles pendientes...")
        val (ok, fail) = repo.sincronizarPendientes()
        Log.d("ExtSyncWorker", "Sincronización completada: $ok enviados, $fail fallidos")

        return if (fail > 0) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "extintores_sync"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<ExtintoresSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
