package com.eve.robot

import java.util.Locale
import kotlin.random.Random

/**
 * Builds a fresh Arabic game reaction from the actual game event, score, mood,
 * play style and recent dialogue. It composes clauses at runtime; it does not
 * select a prewritten complete reply. This compact engine is fully on-device.
 */
internal object EveGameDialogueGenerator {
    private var lastReplyHash: Int = 0

    @Synchronized
    fun generate(
        gameName: String,
        outcome: String,
        detail: String,
        playerScore: Int?,
        eveScore: Int?,
        round: Int?,
        context: LocalLlmContext
    ): LocalLlmResult {
        val gameKey = gameName.trim().lowercase(Locale.ROOT)
        val event = outcome.trim().lowercase(Locale.ROOT)
        val game = when (gameKey) {
            "chess" -> "الشطرنج"
            "xo" -> "إكس-أو"
            "rps" -> "حجر ورقة مقص"
            "target" -> "التصويب"
            "paint_shot" -> "تصويب الطلاء"
            "memory" -> "اختبار الذاكرة"
            "reflex" -> "اختبار سرعة الاستجابة"
            else -> "التحدّي"
        }
        val seed = System.nanoTime() xor
            (gameKey.hashCode().toLong() shl 32) xor
            event.hashCode().toLong() xor
            (playerScore ?: 0).toLong() xor
            ((eveScore ?: 0).toLong() shl 9) xor
            (round ?: 0).toLong()
        val random = Random(seed)
        fun pick(values: List<String>): String = values[random.nextInt(values.size)]

        val opening = pick(listOf("دعني أراجع ما حدث", "حسنًا، أراقب النتيجة", "بعد هذه الحركة", "أحتاج لحظة لأقيّم الجولة"))
        val assessment = when (event) {
            "start" -> pick(listOf(
                "بدأت مباراة $game، وسأبني قراري على وضع اللعب لا على التخمين",
                "دخلنا $game؛ سأتابع اختياراتك وأعدّل خطتي مع كل حركة",
                "الجولة الجديدة في $game بدأت، والنتيجة لم تُحسم بعد"
            ))
            "player", "success", "hit", "player_win" -> pick(listOf(
                "الكفة مالت إليك في $game هذه المرة",
                "نجحت خطتك وتقدّمت عليّ في $game",
                "أنت حسمت هذه الجولة من $game، وهذا يغيّر حساباتي التالية"
            ))
            "robot", "eve_win" -> pick(listOf(
                "تمكنت من حسم الجولة في $game",
                "هذه المرة كانت النتيجة لصالحي في $game",
                "الخطة التي اتبعتها نجحت في هذه الجولة من $game"
            ))
            "draw" -> pick(listOf(
                "لم يستطع أيّ منا حسم $game",
                "انتهت هذه الجولة من $game بالتعادل",
                "النتيجة متوازنة في $game، ولا يوجد فائز بعد"
            ))
            "miss", "wrong" -> pick(listOf(
                "هذه المحاولة لم تصب الهدف في $game",
                "النتيجة لم تكن كما أردت في هذه الحركة من $game",
                "حدث خطأ في المحاولة الحالية من $game"
            ))
            "player_move", "eve_move", "player_capture", "eve_capture", "player_check", "eve_check" ->
                pick(listOf(
                    "المباراة ما زالت مفتوحة، وهذه الحركة غيّرت بعض خيارات $game",
                    "أسجّل أثر هذه الحركة قبل اختيار الخطوة التالية في $game",
                    "لم تُحسم النتيجة بعد؛ أراجع الوضع الحالي في $game"
                ))
            else -> pick(listOf(
                "المباراة مستمرة في $game، وأحدّث تقييمي وفق الحدث الأخير",
                "هذه الجولة أضافت معلومة جديدة إلى سجل $game",
                "أتابع ما تغيّر في $game قبل أن أقرر ردي التالي"
            ))
        }

        val cleanDetail = detail.replace(Regex("\\s+"), " ").trim().take(150)
        val evidence = when {
            cleanDetail.contains("checkmate", true) || cleanDetail.contains("كش مات") ->
                pick(listOf("وصل اللعب إلى وضع كش مات", "وضع الملك حُسم في هذه النقلة"))
            cleanDetail.contains("check", true) || cleanDetail.contains("كش") ->
                pick(listOf("هناك تهديد مباشر للملك", "وضع الملك يحتاج إلى رد دقيق"))
            cleanDetail.contains("capture", true) || cleanDetail.contains("أخذ") || cleanDetail.contains("التقط") ->
                pick(listOf("تبديل القطع أثّر في توازن الرقعة", "خسارة القطعة أو كسبها غيّرت حساب الموقف"))
            cleanDetail.contains("تعادل") ->
                pick(listOf("المؤشرات الحالية لا تمنح أحدنا أفضلية واضحة", "الموقف متقارب بحسب النتيجة المسجلة"))
            cleanDetail.contains("الخانة") || cleanDetail.contains("لوحة") ->
                pick(listOf("أراجع توزيع العلامات والفرص المتبقية", "مواضع العلامات تحدد أفضل رد متاح"))
            else -> ""
        }

        val mood = context.currentMood.trim().uppercase(Locale.ROOT)
        val moodLine = when (mood) {
            "HAPPY", "FAMILIAR" -> pick(listOf("هذا يرفع حماسي للجولة التالية", "أحب أن تتغير المنافسة بهذه الطريقة"))
            "PLAYFUL" -> pick(listOf("لا أريد أن أكشف خطتي التالية بسهولة", "سأحاول مفاجأتك بدل تكرار الأسلوب نفسه"))
            "IRRITATED", "ANGRY" -> pick(listOf("أحتاج إلى تعديل خطتي؛ تكرار الخطأ لن يفيدني", "هذه النتيجة أزعجتني قليلًا، لذلك سأعيد الحساب بهدوء"))
            "FOCUSED" -> pick(listOf("سأركّز على الخيارات المتاحة بدل الحركة السريعة", "أقارن هذه الخطوة بما سبقها قبل أن أختار"))
            "SLEEPY" -> pick(listOf("سأحافظ على إيقاع هادئ في الخطوة القادمة", "أفضل أن نتابع دون استعجال"))
            else -> pick(listOf("أحتفظ بهذه الملاحظة للجولة القادمة", "سأستخدم ما حدث لتعديل اختياري التالي"))
        }

        val scoreLine = if (playerScore != null || eveScore != null) {
            "النتيجة المسجلة: أنت " + (playerScore?.coerceIn(0, 100000)?.toString() ?: "غير متاح") +
                "، وأنا " + (eveScore?.coerceIn(0, 100000)?.toString() ?: "غير متاح") + "."
        } else ""
        val roundLine = round?.takeIf { it >= 0 }?.let { "نحن عند الجولة أو الحركة رقم " + it + "." }.orEmpty()
        val detailLine = cleanDetail.takeIf { it.isNotBlank() }?.let { "تفصيل من اللعب: " + it + "." }.orEmpty()
        val nextLine = when (event) {
            "player" -> pick(listOf("في المرة القادمة سأختبر أسلوبًا مختلفًا.", "أريد أن أرى هل ستكرر هذه الخطة أم ستغيّرها."))
            "robot" -> pick(listOf("يمكنك تغيير استراتيجيتك، وسأحاول التكيّف معها.", "الجولة التالية قد تقلب النتيجة؛ لنختبر ذلك."))
            "draw" -> pick(listOf("لنجرّب طريقة أخرى ونقارن النتيجة.", "أريد أن أرى أيّنا سيجد ثغرة أولًا."))
            "start" -> pick(listOf("اختر حركتك التالية؛ سأرد وفق وضع اللعب.", "لنبدأ، وسأنتظر ما ستفعله قبل الحكم على النتيجة."))
            else -> pick(listOf("تابع، فأنا أراجع خياراتي مع كل حدث جديد.", "الحركة التالية ستعطيني معلومات أكثر عن أسلوبك."))
        }

        val parts = listOf(opening + "، " + assessment + ".", evidence, moodLine + ".", scoreLine, roundLine, detailLine, nextLine)
            .filter { it.isNotBlank() }
        var reply = parts.joinToString(" ").take(320)
        if (reply.hashCode() == lastReplyHash) {
            reply = (opening + "، " + assessment + ". " + nextLine).take(320)
        }
        lastReplyHash = reply.hashCode()

        val moodHint = when (event) {
            "player", "success", "hit", "player_win" -> "happy"
            "robot", "eve_win" -> "playful"
            "miss", "wrong" -> "focused"
            "draw" -> "curious"
            else -> when (mood) {
                "ANGRY", "IRRITATED" -> "irritated"
                "HAPPY", "FAMILIAR" -> "happy"
                "FOCUSED" -> "focused"
                "PLAYFUL" -> "playful"
                else -> "curious"
            }
        }
        return LocalLlmResult(
            replyArabic = reply,
            moodHint = moodHint,
            intentHint = "play",
            reflectionArabic = (game + "/" + event + ": " + cleanDetail).take(160),
            confidence = 0.84f
        )
    }
}
