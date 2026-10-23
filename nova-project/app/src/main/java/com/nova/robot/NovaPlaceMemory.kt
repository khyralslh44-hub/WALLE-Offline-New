package com.nova.robot
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
/** Keeps user-named places and object-label signatures, never images or coordinates. */
internal class NovaPlaceMemory(private val memory:NovaMindMemory) {
 private data class Profile(val name:String,val labels:Set<String>)
 @Synchronized fun enroll(rawName:String,evidence:String,now:Long=System.currentTimeMillis()):Boolean {
  val name=clean(rawName);val labels=parseSet(evidence);if(name.length !in 2..40||labels.size<2)return false
  val updated=(readAll().filterNot{norm(it.name)==norm(name)}+Profile(name,labels)).takeLast(12)
  memory.write(NovaMemoryItem(KEY,updated.joinToString("\n"){p->enc(p.name)+"|"+p.labels.joinToString(","){enc(it)}},now));return true
 }
 @Synchronized fun match(evidence:String):String? {
  val observed=parseSet(evidence);if(observed.size<2)return null
  return readAll().map{p->val n=p.labels.intersect(observed).size;Triple(p,n,n.toFloat()/p.labels.size.coerceAtLeast(1))}
   .filter{it.second>=2&&it.third>=0.5f}.maxByOrNull{it.third}?.first?.name
 }
 @Synchronized fun names():List<String> = readAll().map{it.name}
 @Synchronized fun describe():String=if(names().isEmpty())
  "لم أحفظ أماكن بعد. شغّلي الرؤية واكتبي: «علّمي هذا المكان باسم المطبخ». المطابقة تقريبية."
  else "الأماكن التي علّمتِني إياها محليًا: "+names().joinToString("، ")+". قد تختلط الغرف المتشابهة."
 @Synchronized fun clear(now:Long=System.currentTimeMillis()){memory.write(NovaMemoryItem(KEY,"",now))}
 private fun readAll():List<Profile> = memory.read(KEY).orEmpty().lineSequence().mapNotNull{row->
  val p=row.split("|",limit=2);if(p.size!=2)return@mapNotNull null
  val n=dec(p[0])?:return@mapNotNull null;val ls=p[1].split(",").mapNotNull(::dec).toSet()
  if(n.isBlank()||ls.size<2)null else Profile(n,ls)
 }.toList().takeLast(12)
 private fun parseSet(s:String)=s.split(",").map{norm(it).take(60)}.filter{it.length>=2}.toSet()
 private fun clean(s:String)=s.trim().replace(Regex("[|,\\r\\n]")," ").replace(Regex("\\s+")," ").take(40)
 private fun norm(s:String)=normalizeText(s)
 private fun enc(s:String)=Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(StandardCharsets.UTF_8))
 private fun dec(s:String):String?=runCatching{String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8)}.getOrNull()
 companion object {
  private const val KEY="nova_place_profiles_v1"
  private fun normalizeText(s:String)=s.trim().lowercase(Locale.ROOT).replace(Regex("[\\u064B-\\u065F\\u0670]"),"")
   .replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ى','ي').replace(Regex("\\s+")," ")
  fun signature(labels:List<NovaLabelEvidence>)=labels.asSequence().filter{it.confidence.isFinite()&&it.confidence>=0.48f}.sortedByDescending{it.confidence}
   .map{it.label.trim().lowercase(Locale.ROOT).replace(",", " ").replace("|"," ")}.filter{it.length>=2}.distinct().take(5).joinToString(",")
  fun parseTrainingCommand(raw:String):String? {
   val s=normalizeText(raw).trim().trimEnd('.', '!', '?','؟')
   val prefixes=listOf("علمي هذا المكان باسم ","تعلمي هذا المكان باسم ","احفظي هذا المكان باسم ","سجلي هذا المكان باسم ","علمي المكان باسم ")
   val p=prefixes.firstOrNull{s.startsWith(it)}?:return null;return s.removePrefix(p).trim().takeIf{it.length in 2..40}
  }
  fun isListCommand(raw:String)=normalizeText(raw).trim().trimEnd('.', '!', '?','؟') in setOf("ما الاماكن التي تتذكرينها","ماذا تتذكرين من الاماكن","اذكري الاماكن المحفوظة")
  fun isClearCommand(raw:String)=normalizeText(raw).trim().trimEnd('.', '!', '?','؟') in setOf("امسحي ذاكرة الاماكن","احذفي الاماكن المحفوظة")
 }
}
