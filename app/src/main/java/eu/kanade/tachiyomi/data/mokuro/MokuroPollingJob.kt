package eu.kanade.tachiyomi.data.mokuro

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.mokuro.interactor.GetMokuroJobByChapterId
import tachiyomi.domain.mokuro.interactor.UpsertMokuroJob
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MokuroPollingJob(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val notifier = MokuroNotifier(appContext)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val chapterId = inputData.getLong(KEY_CHAPTER_ID, 0L)
        val chapterName = inputData.getString(KEY_CHAPTER_NAME) ?: "Chapter"
        val notification = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_MOKURO_PROGRESS)
            .setSmallIcon(R.drawable.ic_glasses_24dp)
            .setContentTitle(chapterName)
            .setContentText("Processing with Mokuro…")
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()
        return ForegroundInfo(notifier.notifId(chapterId), notification)
    }

    override suspend fun doWork(): Result = withIOContext {
        val chapterId = inputData.getLong(KEY_CHAPTER_ID, -1L)
        val chapterName = inputData.getString(KEY_CHAPTER_NAME) ?: "Chapter"
        if (chapterId == -1L) return@withIOContext Result.failure()

        setForeground(getForegroundInfo())

        val poller = MokuroJobPoller(
            getJob = Injekt.get<GetMokuroJobByChapterId>(),
            upsertJob = Injekt.get<UpsertMokuroJob>(),
            client = Injekt.get<MokuroApiClient>(),
            preferences = Injekt.get<MokuroPreferences>(),
        )

        return@withIOContext when (poller.poll(chapterId)) {
            "done" -> {
                notifier.showDone(chapterId, chapterName)
                MokuroPageDownloadJob.enqueue(applicationContext, chapterId, chapterName)
                Result.success()
            }
            "failed" -> {
                val job = Injekt.get<GetMokuroJobByChapterId>().await(chapterId)
                notifier.showFailed(chapterId, chapterName, job?.errorMessage)
                Result.failure()
            }
            else -> { // "timeout"
                notifier.showFailed(chapterId, chapterName, "Processing timed out")
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_CHAPTER_ID = "chapter_id"
        const val KEY_CHAPTER_NAME = "chapter_name"

        fun enqueue(context: Context, chapterId: Long, chapterName: String) {
            val request = OneTimeWorkRequestBuilder<MokuroPollingJob>()
                .setInputData(workDataOf(KEY_CHAPTER_ID to chapterId, KEY_CHAPTER_NAME to chapterName))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "mokuro_poll_$chapterId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
