package com.nova.robot

import java.util.Locale

internal data class NovaExpressionRequest(val mood: NovaMood, val labelArabic: String)

internal object NovaExpressionCommand {
    fun parse(raw: String): NovaExpressionRequest? {
        val command = normalize(raw)
        val expression = when {
            command.startsWith("تعبير:") -> command.substringAfter("تعبير:").trim()
            command.startsWith("اظهري تعبير ") -> command.removePrefix("اظهري تعبير ").trim()
            command.startsWith("اظهر تعبير ") -> command.removePrefix("اظهر تعبير ").trim()
            command.startsWith("اعرضي تعبير ") -> command.removePrefix("اعرضي تعبير ").trim()
            else -> return null
        }.removePrefix("ال").trim()

        return when {
            expression.contains("احراج") || expression.contains("خجل") ->
                NovaExpressionRequest(NovaMood.EMBARRASSED, "الخجل")
            expression.contains("غيرة") || expression.contains("اغار") ->
                NovaExpressionRequest(NovaMood.JEALOUS, "الغيرة")
            expression.contains("دوار") || expression.contains("دوخه") || expression.contains("دوخة") ->
                NovaExpressionRequest(NovaMood.DIZZY, "الدوار")
            expression.contains("اعجاب") ->
                NovaExpressionRequest(NovaMood.ADMIRING, "الإعجاب")
            expression.contains("سعاده") || expression.contains("فرح") ->
                NovaExpressionRequest(NovaMood.HAPPY, "الفرح")
            expression.contains("حماس") || expression.contains("اثاره") ->
                NovaExpressionRequest(NovaMood.EXCITED, "الحماس")
            expression.contains("فخر") || expression.contains("فخوره") ->
                NovaExpressionRequest(NovaMood.PROUD, "الفخر")
            expression.contains("اصرار") || expression.contains("مصممه") || expression.contains("مصمم") ->
                NovaExpressionRequest(NovaMood.DETERMINED, "الإصرار")
            expression.contains("فضول") ->
                NovaExpressionRequest(NovaMood.CURIOUS, "الفضول")
            expression.contains("تفكير") ->
                NovaExpressionRequest(NovaMood.THINKING, "التفكير")
            expression.contains("حزن") ->
                NovaExpressionRequest(NovaMood.SAD, "الحزن")
            expression.contains("غضب") ->
                NovaExpressionRequest(NovaMood.ANGRY, "الغضب")
            expression.contains("انزعاج") || expression.contains("احباط") ->
                NovaExpressionRequest(NovaMood.FRUSTRATED, "الانزعاج")
            expression.contains("دهشه") ->
                NovaExpressionRequest(NovaMood.SURPRISED, "الدهشة")
            expression.contains("نعاس") ->
                NovaExpressionRequest(NovaMood.SLEEPY, "النعاس")
            expression.contains("خوف") ->
                NovaExpressionRequest(NovaMood.AFRAID, "الخوف")
            expression.contains("حب") ->
                NovaExpressionRequest(NovaMood.LOVE, "الحب")
            else -> null
        }
    }

    private fun normalize(value: String) = value.trim().lowercase(Locale.ROOT)
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ى', 'ي').replace(Regex("\\s+"), " ")
}
