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
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.mokuro.interactor.GetMokuroJobByChapterId
import tachiyomi.domain.mokuro.interactor.UpsertMokuroJob
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Pure download logic extracted for testability.
 * Returns true on success, false on failure (partial files cleaned up on failure).
 */
class MokuroPageDownloader(
    private val getJob: GetMokuroJobByChapterId,
    private val upsertJob: UpsertMokuroJob,
    private val client: MokuroApiClient,
    private val preferences: MokuroPreferences,
    private val onProgress: ((current: Int, total: Int) -> Unit)? = null,
) {
    suspend fun download(chapterId: Long, filesDir: File): Boolean {
        val serverUrl = preferences.serverUrl().get()
        val token = preferences.token().get()
        val job = getJob.await(chapterId) ?: return false
        val pageCount = job.pageCount ?: return false
        val destDir = File(filesDir, "mokuro/${job.jobId}")

        return try {
            destDir.mkdirs()
            for (page in 1..pageCount) {
                val html = client.fetchPageHtml(serverUrl, token, job.jobId, page)
                val filename = "page_%03d.html".format(page)
                File(destDir, filename).writeText(html)
                onProgress?.invoke(page, pageCount)
            }
            upsertJob.await(job.copy(isOfflineAvailable = true))
            true
        } catch (e: CancellationException) {
            destDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            destDir.deleteRecursively()
            false
        }
    }
}

class MokuroPageDownloadJob(
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
            .setContentText("Downloading offline…")
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

        val downloader = MokuroPageDownloader(
            getJob = Injekt.get<GetMokuroJobByChapterId>(),
            upsertJob = Injekt.get<UpsertMokuroJob>(),
            client = Injekt.get<MokuroApiClient>(),
            preferences = Injekt.get<MokuroPreferences>(),
            onProgress = { current, total ->
                notifier.showDownloadProgress(chapterId, chapterName, current, total)
            },
        )

        val success = downloader.download(
            chapterId = chapterId,
            filesDir = applicationContext.filesDir,
        )

        return@withIOContext if (success) {
            notifier.showOfflineReady(chapterId, chapterName)
            Result.success()
        } else {
            notifier.showOfflineFailed(chapterId, chapterName)
            Result.failure()
        }
    }

    companion object {
        const val KEY_CHAPTER_ID = "chapter_id"
        const val KEY_CHAPTER_NAME = "chapter_name"

        fun enqueue(context: Context, chapterId: Long, chapterName: String) {
            val request = OneTimeWorkRequestBuilder<MokuroPageDownloadJob>()
                .setInputData(workDataOf(
                    KEY_CHAPTER_ID to chapterId,
                    KEY_CHAPTER_NAME to chapterName,
                ))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "mokuro_download_$chapterId",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
