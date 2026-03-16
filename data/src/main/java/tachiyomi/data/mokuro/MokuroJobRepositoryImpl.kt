package tachiyomi.data.mokuro

import kotlinx.coroutines.flow.Flow
import tachiyomi.data.handlers.manga.MangaDatabaseHandler
import tachiyomi.domain.mokuro.model.MokuroJob
import tachiyomi.domain.mokuro.repository.MokuroJobRepository

class MokuroJobRepositoryImpl(
    private val handler: MangaDatabaseHandler,
) : MokuroJobRepository {

    override suspend fun getByChapterId(chapterId: Long): MokuroJob? =
        handler.awaitOneOrNull { mokuroJobsQueries.selectByChapterId(chapterId, ::mapRow) }

    override suspend fun getAll(): List<MokuroJob> =
        handler.awaitList { mokuroJobsQueries.selectAll(::mapRow) }

    override fun subscribeAll(): Flow<List<MokuroJob>> =
        handler.subscribeToList { mokuroJobsQueries.selectAll(::mapRow) }

    override suspend fun upsert(job: MokuroJob) =
        handler.await {
            mokuroJobsQueries.insertOrReplace(
                chapter_id = job.chapterId,
                job_id = job.jobId,
                state = job.state,
                page_count = job.pageCount?.toLong(),
                error_message = job.errorMessage,
                server_url = job.serverUrl,
                created_at = job.createdAt,
            )
        }

    override suspend fun delete(chapterId: Long) =
        handler.await { mokuroJobsQueries.deleteByChapterId(chapterId) }
}

private fun mapRow(
    chapterId: Long, jobId: String, state: String, pageCount: Long?,
    errorMessage: String?, serverUrl: String, createdAt: Long,
) = MokuroJob(
    chapterId = chapterId, jobId = jobId, state = state,
    pageCount = pageCount?.toInt(), errorMessage = errorMessage,
    serverUrl = serverUrl, createdAt = createdAt,
)
