package com.nova.robot
import java.util.Locale
/** Tentative local image-label hints; not a weather forecast. */
internal object NovaEnvironmentRecognizer {
 private data class Cue(val words:Set<String>,val message:String)
 private val cues=listOf(
 Cue(setOf("rain","rainy","rainfall","rainstorm","drizzle","downpour"),"قد تشير التصنيفات البصرية إلى المطر، لكنني لا أستطيع تأكيد حالة الطقس من الصورة وحدها."),
 Cue(setOf("cloud","clouds","cloudy","cloudscape"),"ألاحظ مؤشرات بصرية قد تدل على السحب؛ هذا لا يكفي وحده لتحديد الطقس."),
 Cue(setOf("sun","sunlight","sunny","sunshine","daylight","daytime","sunset","sunrise"),"ألاحظ مؤشرًا بصريًا لضوء الشمس أو النهار؛ قد تؤثر الإضاءة في هذا التقدير."),
 Cue(setOf("night","nighttime","nightfall"),"يوحي تصنيف الصورة بمشهد ليلي محتمل، لكن الظلام وحده لا يثبت أن الوقت ليل."),
 Cue(setOf("sky"),"ألاحظ السماء في المشهد، لكنني لم أجد دليلًا كافيًا لتحديد حالة الطقس."))
 fun isSnowCue(rawLabel:String):Boolean {
  val words=rawLabel.trim().lowercase(Locale.ROOT).split(Regex("[^\\\\p{L}\\\\p{N}]+" )).filter{it.isNotBlank()}.toSet()
  return words.any{it in setOf("snow","snowing","snowfall","snowflake","snowflakes","blizzard")}
 }
 fun isExplicitSnowfallCue(rawLabel:String):Boolean {
  val words=rawLabel.trim().lowercase(Locale.ROOT).split(Regex("[^\\\\p{L}\\\\p{N}]+" )).filter{it.isNotBlank()}.toSet()
  return words.any{it in setOf("snowing","snowfall","blizzard")}
 }
 fun summarize(labels:List<NovaLabelEvidence>):String? {
  val found=labels.asSequence().filter{it.confidence.isFinite()&&it.confidence>=0.53f}
   .map{it.copy(label=it.label.trim().lowercase(Locale.ROOT))}.filter{it.label.isNotBlank()}
   .mapNotNull{e->val words=e.label.split(Regex("[^\\p{L}\\p{N}]+")).filter{it.isNotBlank()}.toSet()
    cues.firstOrNull{c->c.words.any{it in words}}?.let{it to e.confidence}}
   .toList().maxByOrNull{it.second}?:return null
  return found.first.message+" (مؤشر بصري تقريبي: "+(found.second.coerceIn(0f,1f)*100).toInt()+"٪)."
 }
}
