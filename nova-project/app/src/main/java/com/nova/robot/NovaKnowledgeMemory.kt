package com.nova.robot
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
/** Explicitly taught local Q&A facts. They remain user-provided, not independently verified. */
internal class NovaKnowledgeMemory(private val memory:NovaMindMemory){
 private data class Fact(val question:String,val answer:String,val savedAt:Long)
 @Synchronized fun handleCommand(rawText:String,nowMillis:Long=System.currentTimeMillis()):String?{
  val normalized=normalize(rawText).trim()
  if(normalized in clearCommands){memory.write(NovaMemoryItem(KEY_FACTS,"",nowMillis));return "مسحت المعلومات التي علّمتِني إياها. لن أستخدمها في الإجابات المقبلة."}
  if(normalized in listCommands){
   val facts=readFacts()
   return if(facts.isEmpty())"لم تعلّميني إجابات بعد. يمكنك تعليمي بكتابة: «علّميني: السؤال = الإجابة»."
   else "هذه معلومات علّمتِني إياها محليًا: "+facts.takeLast(8).joinToString("؛"){ "عن «"+it.question+"»: "+it.answer }+". لم أتحقق منها من مصدر مستقل."
  }
  val match=TEACH_COMMAND.matchEntire(normalized)?:return null
  val question=cleanQuestion(match.groupValues[1])
  val answer=match.groupValues[2].trim().take(MAX_ANSWER_CHARS)
  if(question.length<3||answer.isBlank())return "لم أحفظ شيئًا؛ اكتبي الأمر بهذا الشكل: «علّميني: السؤال = الإجابة»."
  val facts=readFacts().filterNot{normalizeQuestion(it.question)==normalizeQuestion(question)}
  writeFacts((facts+Fact(question,answer,nowMillis)).takeLast(MAX_FACTS),nowMillis)
  return "تعلّمت منك محليًا: «"+question+"» — «"+answer+"». سأذكر عند استخدامها أنها معلومة منك لم أتحقق منها مستقلًا. لإصلاحها أعيدي أمر التعليم بالسؤال نفسه."
 }
 @Synchronized fun answerFor(rawQuestion:String):String?{
  val q=normalizeQuestion(rawQuestion);if(q.length<3)return null
  val fact=readFacts().lastOrNull{normalizeQuestion(it.question)==q}?:return null
  return "بحسب ما علّمتِني إياه سابقًا، جواب «"+fact.question+"» هو: "+fact.answer+". هذه معلومة محفوظة منك ولم أتحقق منها من مصدر مستقل."
 }
 @Synchronized fun count():Int=readFacts().size
 private fun readFacts():List<Fact> = memory.read(KEY_FACTS).orEmpty().lineSequence().mapNotNull{line->
  val p=line.split("|",limit=3);if(p.size!=3)return@mapNotNull null
  val q=decode(p[0])?:return@mapNotNull null;val a=decode(p[1])?:return@mapNotNull null;val t=p[2].toLongOrNull()?:0L
  if(q.isBlank()||a.isBlank())null else Fact(q,a,t)
 }.toList().takeLast(MAX_FACTS)
 private fun writeFacts(facts:List<Fact>,nowMillis:Long){
  val packed=facts.joinToString("\n"){encode(it.question)+"|"+encode(it.answer)+"|"+it.savedAt}
  memory.write(NovaMemoryItem(KEY_FACTS,packed,nowMillis))
 }
 private fun cleanQuestion(s:String)=s.trim().replace(Regex("\\s+")," ").take(MAX_QUESTION_CHARS).trimEnd('=',' ')
 private fun normalizeQuestion(s:String)=normalize(s).trim().trimEnd('؟','?','.','!','،',',','؛',':',' ')
 private fun normalize(s:String)=s.trim().lowercase(Locale.ROOT).replace(Regex("[\\u064B-\\u065F\\u0670]"),"")
  .replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ى','ي').replace('ـ',' ').replace(Regex("\\s+")," ")
 private fun encode(s:String)=Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(StandardCharsets.UTF_8))
 private fun decode(s:String):String?=runCatching{String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8)}.getOrNull()
 companion object{
  private const val KEY_FACTS="nova_user_taught_q_and_a_v1"
  private const val MAX_FACTS=30
  private const val MAX_QUESTION_CHARS=180
  private const val MAX_ANSWER_CHARS=320
  private val TEACH_COMMAND=Regex("^(?:علميني|علمني)\\s*[:：]\\s*(.+?)\\s*(?:=>|=|←)\\s*(.+)$")
  private val clearCommands=setOf("امسحي ما علمتك اياه","امسحي المعلومات التي علمتك اياها","انس الاجابات التي حفظتها")
  private val listCommands=setOf("ماذا علمتك سابقا","ما المعلومات التي علمتك اياها","اعرضي ما تعلمته مني")
 }
}
