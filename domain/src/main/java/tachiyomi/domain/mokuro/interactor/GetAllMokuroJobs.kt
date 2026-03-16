package tachiyomi.domain.mokuro.interactor
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.mokuro.model.MokuroJob
import tachiyomi.domain.mokuro.repository.MokuroJobRepository
class GetAllMokuroJobs(private val repository: MokuroJobRepository) {
    suspend fun await(): List<MokuroJob> = repository.getAll()
    fun subscribe(): Flow<List<MokuroJob>> = repository.subscribeAll()
}
