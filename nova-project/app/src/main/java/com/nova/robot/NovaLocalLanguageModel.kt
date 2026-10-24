package com.nova.robot

import android.content.Context
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Optional fully on-device language generation for Nova.
 *
 * Place a compatible LiteRT-LM model at:
 * Android/data/com.nova.robot.v3/files/models/nova.litertlm
 *
 * The model weights are intentionally not embedded in the APK: they are hundreds of MB
 * or larger and have their own licenses. No network permission or cloud fallback is used.
 */
internal class NovaLocalLanguageModel(context: Context) {
    private val modelFile: File = File(
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models"),
        "nova.litertlm"
    )

    fun isModelInstalled(): Boolean = modelFile.isFile && modelFile.length() > 0L

    suspend fun reply(
        userText: String,
        visualSummary: String?,
        relevantMemory: String? = null
    ): String? = withContext(Dispatchers.IO) {
        if (!isModelInstalled()) return@withContext null

        val prompt = buildString {
            appendLine("أنت نوفا، روبوت رفيق يعمل بالكامل دون إنترنت على هاتف أندرويد.")
            appendLine("شخصيتك ودودة وفضولية وهادئة، وتفكر اعتمادًا على الأدلة والذاكرة لا على التخمين.")
            appendLine("أجب بالعربية الطبيعية وباختصار. لا تدّعي الوعي البشري أو المشاعر البشرية الحقيقية.")
            appendLine("إذا لم تعرف شيئًا، قل بوضوح إنك لا تعرف، واسأل سؤالًا محددًا يساعدك على التعلم.")
            appendLine("لا تخترع ما رأته الكاميرا ولا هوية الأشخاص. ميّز بين الملاحظة والاستنتاج.")
            appendLine("لا تكرر سؤالًا سبق أن أُجيب عنه إذا كانت الذاكرة المتاحة تحتوي على الجواب.")
            appendLine("أنتج ردًا واحدًا مباشرًا، بلا وصف لتفكيرك الداخلي.")
            appendLine("ملخص الرؤية المحلية: ${visualSummary ?: "لا توجد ملاحظة بصرية متاحة"}.")
            appendLine("معلومات ذات صلة من الذاكرة المحلية: ${relevantMemory ?: "لا توجد ذاكرة ذات صلة مرفقة"}.")
            appendLine("رسالة المستخدم: $userText")
            append("رد نوفا:")
        }

        runCatching {
            val output = StringBuilder()
            Engine(EngineConfig(modelPath = modelFile.absolutePath)).use { engine ->
                engine.initialize()
                engine.createConversation().use { conversation ->
                    conversation.sendMessageAsync(prompt).collect { chunk ->
                        output.append(chunk)
                    }
                }
            }
            output.toString().trim().takeIf { it.isNotBlank() }?.take(1800)
        }.getOrNull()
    }

    companion object {
        const val MODEL_FILE_NAME = "nova.litertlm"
    }
}
