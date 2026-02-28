object ModelManager {

    private var isLoaded = false

    fun isModelLoaded(): Boolean = isLoaded

    suspend fun loadModel(
        modelPath: String,
        onProgress: (Int) -> Unit
    ): Boolean {
        return try {
            for (i in 1..100 step 5) {
                kotlinx.coroutines.delay(50)
                onProgress(i)
            }

            LlamaNative.loadModel(modelPath)
            isLoaded = true
            true
        } catch (e: Exception) {
            false
        }
    }
}
