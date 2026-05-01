data class FlashPartitionProgress(
    val stages: List<String> = emptyList(),
    val currentIndex: Int = -1,
    val completedCount: Int = 0,
) {
    val isEmpty: Boolean
        get() = stages.isEmpty()

    val currentStage: String?
        get() = stages.getOrNull(currentIndex)

    val completedStages: List<String>
        get() = stages.take(completedCount.coerceIn(0, stages.size))

    val pendingStages: List<String>
        get() = if (currentIndex < 0) {
            stages
        } else {
            stages.drop((currentIndex + 1).coerceAtLeast(completedCount))
        }
}

data class ZstdExtractionProgress(
    val isActive: Boolean = false,
    val currentFile: String = "",
    val completedCount: Int = 0,
    val totalCount: Int = 0,
) {
    val fraction: Float
        get() = if (totalCount <= 0) 0f else completedCount.toFloat() / totalCount.toFloat()
}

data class WaitProgress(
    val isActive: Boolean = false,
    val title: String = "",
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
) {
    val fraction: Float
        get() = if (totalSteps <= 0) 0f else currentStep.toFloat() / totalSteps.toFloat()
}

data class ManagerDownloadProgress(
    val isActive: Boolean = false,
    val title: String = "",
    val fileName: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else downloadedBytes.toFloat() / totalBytes.toFloat()
}
