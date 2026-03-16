package tachiyomi.domain.mokuro.interactor
import tachiyomi.domain.mokuro.model.MokuroJob
import tachiyomi.domain.mokuro.repository.MokuroJobRepository
class UpsertMokuroJob(private val repository: MokuroJobRepository) {
    suspend fun await(job: MokuroJob) = repository.upsert(job)
}
