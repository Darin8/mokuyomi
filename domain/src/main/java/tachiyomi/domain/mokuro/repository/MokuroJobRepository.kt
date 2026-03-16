package tachiyomi.domain.mokuro.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.mokuro.model.MokuroJob

interface MokuroJobRepository {
    suspend fun getByChapterId(chapterId: Long): MokuroJob?
    suspend fun getAll(): List<MokuroJob>
    fun subscribeAll(): Flow<List<MokuroJob>>
    suspend fun upsert(job: MokuroJob)
    suspend fun delete(chapterId: Long)
}
