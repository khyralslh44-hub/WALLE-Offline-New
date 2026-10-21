package com.eve.robot
import java.util.Locale

enum class EveTravelFactType(val storageKey:String) { TRAVEL_HISTORY("past"), TRAVEL_INTENTION("intent") }
data class EveTravelFact(val type:EveTravelFactType,val statement:String,val timestampMs:Long)
/** Stores explicit dialogue only: camera imagery never proves prior travel or a person's intentions. */
class EveTravelMemory(serialized:String="") {
 private val facts=mutableListOf<EveTravelFact>()
 init { restore(serialized) }
 @Synchronized fun recordStatement(rawText:String,timestampMs:Long):EveTravelFact? {
  val original=rawText.trim().replace(Regex("\\s+")," ").take(MAX_CHARS)
  val t=normalize(original)
  if(t.isBlank() || !mentionsTravel(t)) return null
  val save=any(t,"احفظ","تذكر","تذكري","سجل","سجلي","خزن","خزني")
  val intent=any(t,"اريد السفر","اريد اسافر","اريد ان اسافر","ابغى اسافر","ابغي اسافر","ابي اسافر","ودي اسافر",
   "نفسي اسافر","ناوي اسافر","ناويه اسافر","انوي السفر","افكر اسافر","افكر في السفر","اتمني اسافر","اتمني السفر",
   "احب اسافر","ارغب بالسفر","ارغب في السفر","خاطري اسافر","اخطط للسفر","لا اريد السفر","ما اريد السفر",
   "لا ارغب بالسفر","ما ارغب بالسفر")
  val past=any(t,"سافرت","سافرنا","سافر الي","سافر الى","زرت","كنت مسافر","كنت مسافره","سبق لي السفر",
   "سبق وسافرت","رجعت من السفر","عدت من السفر","رحلتي الي","رحلاتي الي")
  val negativePast=any(t,"لم اسافر","ما سافرت","لم ازر","ما زرت","لم اسافر من قبل")
  val firstPerson=any(t,"انا ","اني ","لي السفر","سبق لي")
  val type=when { intent -> EveTravelFactType.TRAVEL_INTENTION; past && (save || firstPerson) -> EveTravelFactType.TRAVEL_HISTORY
   negativePast && save -> EveTravelFactType.TRAVEL_HISTORY; else -> null } ?: return null
  val fact=EveTravelFact(type,original.replace('|',' ').replace('\n',' ').take(MAX_CHARS),timestampMs.coerceAtLeast(0L))
  facts+=fact
  val keep=facts.sortedBy { it.timestampMs }.takeLast(MAX_FACTS); facts.clear(); facts.addAll(keep)
  return fact
 }
 @Synchronized fun answerQuestion(raw:String):String? {
  val q=normalize(raw)
  val type=when { isIntentQuestion(q)->EveTravelFactType.TRAVEL_INTENTION; isHistoryQuestion(q)->EveTravelFactType.TRAVEL_HISTORY; else->return null }
  val fact=facts.filter { it.type==type }.maxByOrNull { it.timestampMs }
  if(fact==null) return if(type==EveTravelFactType.TRAVEL_HISTORY)
   "لا أملك ذاكرة صريحة تؤكد أن هذا الشخص سافر. لا يمكنني استنتاج تاريخ السفر من الوجه أو المظهر؛ أخبرني الشخص بذلك في الحوار لأحفظ ما قاله محليًا."
  else "لا أعرف إن كان الشخص يرغب بالسفر ما لم يذكر ذلك صراحةً. لا أستنتج النوايا من الوجه أو الأشياء الظاهرة."
  val label=if(type==EveTravelFactType.TRAVEL_HISTORY) "عن سفر سابق" else "عن رغبته في السفر"
  return "بحسب ما ذُكر في الحوار وحُفظ محليًا "+label+": «"+fact.statement+"». هذه ذاكرة لما قيل، وليست تحققًا مستقلًا من السفر أو النية."
 }
 @Synchronized fun clear(){ facts.clear() }
 @Synchronized fun serialize():String=facts.sortedBy { it.timestampMs }.takeLast(MAX_FACTS).joinToString("\n") {
  it.timestampMs.toString()+"|"+it.type.storageKey+"|"+it.statement.replace('|',' ').replace('\n',' ').take(MAX_CHARS)
 }
 @Synchronized private fun restore(value:String) {
  facts.clear()
  value.lineSequence().filter { it.isNotBlank() }.forEach { row ->
   val p=row.split("|",limit=3); if(p.size<3) return@forEach
   val time=p[0].toLongOrNull() ?: return@forEach
   val type=when(p[1]){"past"->EveTravelFactType.TRAVEL_HISTORY;"intent"->EveTravelFactType.TRAVEL_INTENTION;else->return@forEach}
   if(p[2].isNotBlank()) facts+=EveTravelFact(type,p[2].trim().take(MAX_CHARS),time)
  }
  if(facts.size>MAX_FACTS){val keep=facts.sortedBy { it.timestampMs }.takeLast(MAX_FACTS);facts.clear();facts.addAll(keep)}
 }
 private fun isHistoryQuestion(q:String)=mentionsTravel(q)&&any(q,"هل سافرت","هل سبق وسافرت","هل سافر","هل كان مسافر","هل كانت مسافره",
  "وين سافرت","اين سافرت","ما الدول التي زرتها","هل زرت","سبق له السفر","هل سبق له","هل الشخص مسافر","هل الشخص كان مسافر")
 private fun isIntentQuestion(q:String)=mentionsTravel(q)&&any(q,"هل يريد السفر","هل الشخص يرغب بالسفر","هل ترغب بالسفر","هل يرغب بالسفر",
  "هل اريد السفر","هل ارغب بالسفر","هل ينوي السفر","هل ناوي يسافر","هل تبي تسافر","هل تريد السفر","هل افكر بالسفر","رغبته بالسفر","نيته السفر","يخطط للسفر")
 private fun mentionsTravel(t:String)=any(t,"سفر","سافر","مسافر","مسافره","رحله","رحلات","يسافر","اسافر","ازور","زرت")
 private fun any(t:String,vararg xs:String)=xs.any(t::contains)
 private fun normalize(s:String)=s.trim().lowercase(Locale.ROOT).replace(Regex("[\\u064B-\\u065F\\u0670]"),"")
  .replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ى','ي').replace('ة','ه')
  .replace(Regex("[^\\p{L}\\p{N}\\s]")," ").replace(Regex("\\s+")," ").trim()
 companion object {private const val MAX_FACTS=20; private const val MAX_CHARS=220}
}
