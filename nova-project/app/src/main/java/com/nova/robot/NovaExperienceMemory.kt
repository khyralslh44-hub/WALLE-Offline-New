package com.nova.robot
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
/** Opt-in generic visual summaries; no raw images, identity matches, audio, or chat transcripts. */
internal class NovaExperienceMemory(private val memory:NovaMindMemory) {
 private data class Entry(val at:Long,val summary:String)
 @Synchronized fun enabled()=memory.read(ENABLED)=="true"
 @Synchronized fun setEnabled(on:Boolean,now:Long=System.currentTimeMillis()){memory.write(NovaMemoryItem(ENABLED,on.toString(),now))}
 @Synchronized fun observe(summary:String,now:Long=System.currentTimeMillis()):Boolean {
  if(!enabled())return false
  val s=summary.trim().replace(Regex("[\\r\\n]+")," ").replace(Regex("\\s+")," ").take(220)
  if(s.length<8)return false
  val rows=entries().toMutableList();val last=rows.lastOrNull()
  if(last!=null&&norm(last.summary)==norm(s))return false
  if(last!=null&&now>=last.at&&now-last.at<25_000L)return false
  rows.add(Entry(now,s));memory.write(NovaMemoryItem(ITEMS,rows.takeLast(20).joinToString("\n"){it.at.toString()+"|"+enc(it.summary)},now));return true
 }
 @Synchronized fun summaries()=entries().map{it.summary}
 @Synchronized fun clear(now:Long=System.currentTimeMillis()){memory.write(NovaMemoryItem(ENABLED,"false",now));memory.write(NovaMemoryItem(ITEMS,"",now))}
 @Synchronized fun handleCommand(raw:String,now:Long=System.currentTimeMillis()):String? {
  val c=norm(raw).trim().trimEnd('.', '!', '?','؟')
  return when(c){
   "فعلي ذاكرة المشاهد","فعلي ذاكرة الخبرات","تذكري المشاهد التي ترينها"->{setEnabled(true,now);"فعّلت ذاكرة المشاهد بموافقتك. أحفظ ملخصات بصرية قصيرة فقط، لا الصور أو أسماء الوجوه. يمكنك إيقافها أو مسحها."}
   "اوقفي ذاكرة المشاهد","عطلي ذاكرة المشاهد","اوقفي ذاكرة الخبرات"->{setEnabled(false,now);"أوقفت حفظ المشاهد الجديدة؛ بقيت الملخصات السابقة حتى تطلبي مسحها."}
   "ماذا تتذكرين من المشاهد","ما المشاهد التي تتذكرينها","اعرضي ذاكرة المشاهد"->{val a=summaries();if(a.isEmpty())"لا توجد مشاهد محفوظة. اكتبي «فعّلي ذاكرة المشاهد» للموافقة على الحفظ المحلي." else "ملخصات المشاهد المحفوظة محليًا: "+a.takeLast(5).joinToString("؛ ")}
   "امسحي ذاكرة المشاهد","احذفي ذاكرة المشاهد"->{clear(now);"مسحت ذاكرة المشاهد وأوقفت الحفظ الجديد."}
   else->null
  }
 }
 private fun entries():List<Entry> = memory.read(ITEMS).orEmpty().lineSequence().mapNotNull{r->
  val p=r.split("|",limit=2);if(p.size!=2)return@mapNotNull null
  val t=p[0].toLongOrNull()?:return@mapNotNull null;val s=dec(p[1])?:return@mapNotNull null
  if(s.isBlank())null else Entry(t,s)
 }.toList().takeLast(20)
 private fun enc(s:String)=Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(StandardCharsets.UTF_8))
 private fun dec(s:String):String?=runCatching{String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8)}.getOrNull()
 private fun norm(s:String)=s.trim().lowercase(Locale.ROOT).replace(Regex("[\\u064B-\\u065F\\u0670]"),"")
  .replace('أ','ا').replace('إ','ا').replace('آ','ا').replace(Regex("\\s+")," ")
 companion object {private const val ENABLED="nova_experience_memory_enabled_v1";private const val ITEMS="nova_experience_memory_items_v1"}
}
