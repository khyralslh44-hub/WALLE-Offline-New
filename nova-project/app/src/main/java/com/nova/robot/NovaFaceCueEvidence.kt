package com.nova.robot

/**
 * Converts ML Kit's optional facial landmarks classifications into cautious visual cues.
 * These probabilities are not a diagnosis of a person's emotion or mental state.
 */
internal object NovaFaceCueEvidence {
    fun summarize(
        smilingProbability: Float?,
        leftEyeOpenProbability: Float?,
        rightEyeOpenProbability: Float?
    ): String? {
        fun valid(value: Float?): Float? = value?.takeIf { it.isFinite() && it in 0f..1f }
        val smile = valid(smilingProbability)
        val left = valid(leftEyeOpenProbability)
        val right = valid(rightEyeOpenProbability)
        val cues = mutableListOf<String>()

        if (smile != null) {
            when {
                smile >= 0.78f -> cues += "تظهر إشارة ابتسام محتملة"
                smile <= 0.18f -> cues += "لم تظهر إشارة ابتسام واضحة"
            }
        }
        if (left != null && right != null) {
            if (left <= 0.22f && right <= 0.22f) {
                cues += "العينان تبدوان شبه مغلقتين في هذه اللقطة"
            }
        }
        if (cues.isEmpty()) return null
        return "ألاحظ بعض الإشارات: " + cues.joinToString("، ") +
            ". إنها ملاحظات شكلية تقريبية، ولا تثبت ما يشعر به الشخص."
    }
}
