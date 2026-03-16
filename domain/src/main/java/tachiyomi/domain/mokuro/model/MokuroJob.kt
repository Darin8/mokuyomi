package tachiyomi.domain.mokuro.model

data class MokuroJob(
    val chapterId: Long,
    val jobId: String,
    val state: String,
    val pageCount: Int?,
    val errorMessage: String?,
    val serverUrl: String,
    val createdAt: Long,
) {
    val isDone: Boolean get() = state == "done"
    val isFailed: Boolean get() = state == "failed"
    val isInProgress: Boolean get() = state == "pending" || state == "processing"
}
