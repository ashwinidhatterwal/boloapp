package com.offlinenarrator.benchmark.benchmark

data class PerformanceLabEntry(
    val modelLabel: String,
    val modelSha256: String?,
    val profileId: String,
    val profileLabel: String,
    val initMs: Long?,
    val generationMs: Long?,
    val audioMs: Long?,
    val rtf: Double?,
    val rssMb: Double?,
    val pssMb: Double?,
    val nativePssMb: Double?,
    val temperatureC: Double?,
    val thermalStatus: String?,
    val error: String? = null,
) {
    val succeeded: Boolean get() = error == null && rtf != null && rtf.isFinite()
}

data class PerformanceValidation(
    val stressMeanRtf: Double? = null,
    val stressBestRtf: Double? = null,
    val stressWorstRtf: Double? = null,
    val longGenerationMs: Long? = null,
    val longAudioMs: Long? = null,
    val longRtf: Double? = null,
    val finalRssMb: Double? = null,
    val finalPssMb: Double? = null,
    val finalTemperatureC: Double? = null,
    val finalThermalStatus: String? = null,
    val error: String? = null,
)

data class PerformanceLabReport(
    val entries: List<PerformanceLabEntry> = emptyList(),
    val winnerModelLabel: String? = null,
    val winnerProfileId: String? = null,
    val winnerProfileLabel: String? = null,
    val winnerRtf: Double? = null,
    val validation: PerformanceValidation? = null,
)
