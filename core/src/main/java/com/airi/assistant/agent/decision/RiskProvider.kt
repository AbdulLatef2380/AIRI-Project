interface RiskProvider {
    fun estimate(action: String): RiskResult
}

data class RiskResult(
    val riskScore: Float,
    val isCritical: Boolean
)
