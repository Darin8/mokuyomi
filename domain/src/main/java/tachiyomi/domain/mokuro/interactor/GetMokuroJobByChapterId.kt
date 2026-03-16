package tachiyomi.domain.mokuro.interactor
import tachiyomi.domain.mokuro.model.MokuroJob
import tachiyomi.domain.mokuro.repository.MokuroJobRepository
class GetMokuroJobByChapterId(private val repository: MokuroJobRepository) {
    suspend fun await(chapterId: Long): MokuroJob? = repository.getByChapterId(chapterId)
}
