package tachiyomi.data.mokuro

import kotlinx.coroutines.flow.Flow
import tachiyomi.data.handlers.manga.MangaDatabaseHandler
import tachiyomi.domain.mokuro.model.MokuroJob
import tachiyomi.domain.mokuro.repository.MokuroJobRepository

class MokuroJobRepositoryImpl(
    private val handler: MangaDatabaseHandler,
) : MokuroJobRepository {

    override suspend fun getByChapterId(chapterId: Long): MokuroJob? =
        handler.awaitOneOrNull { mokuro_jobsQueries.selectByChapterId(chapterId, ::mapRow) }

    override suspend fun getAll(): List<MokuroJob> =
        handler.awaitList { mokuro_jobsQueries.selectAll(::mapRow) }

    override fun subscribeAll(): Flow<List<MokuroJob>> =
        handler.subscribeToList { mokuro_jobsQueries.selectAll(::mapRow) }

    override suspend fun upsert(job: MokuroJob) =
        handler.await {
            mokuro_jobsQueries.insertOrReplace(
                chapter_id = job.chapterId,
                job_id = job.jobId,
                state = job.state,
                page_count = job.pageCount?.toLong(),
                error_message = job.errorMessage,
                server_url = job.serverUrl,
                created_at = job.createdAt,
                is_offline_available = if (job.isOfflineAvailable) 1L else 0L,
            )
        }

    override suspend fun delete(chapterId: Long) =
        handler.await { mokuro_jobsQueries.deleteByChapterId(chapterId) }
}

private fun mapRow(
    chapterId: Long, jobId: String, state: String, pageCount: Long?,
    errorMessage: String?, serverUrl: String, createdAt: Long,
    isOfflineAvailable: Long,
) = MokuroJob(
    chapterId = chapterId, jobId = jobId, state = state,
    pageCount = pageCount?.toInt(), errorMessage = errorMessage,
    serverUrl = serverUrl, createdAt = createdAt,
    isOfflineAvailable = isOfflineAvailable != 0L,
)
