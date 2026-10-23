package com.nova.robot
import java.util.ArrayDeque
import java.util.Locale
/** Best-effort visual cue only when a generic classifier explicitly labels cough/sneeze repeatedly. */
internal class NovaTransientActionTracker(
 private val confirmationWindowMillis:Long=8_000L,
 private val activeNoteMillis:Long=7_000L,
 private val minimumConfidence:Float=0.56f
){
 private enum class Action{COUGH,SNEEZE}
 private val hits=ArrayDeque<Pair<Long,Action>>()
 private var lastPositiveAt=Long.MIN_VALUE
 private var confirmedUntil=Long.MIN_VALUE
 private var confirmedAction:Action?=null
 @Synchronized fun observe(labels:List<NovaLabelEvidence>,facePresent:Boolean,nowMillis:Long):String?{
  if(!facePresent){reset();return null}
  if(lastPositiveAt!=Long.MIN_VALUE&&nowMillis<lastPositiveAt)reset()
  while(hits.isNotEmpty()&&nowMillis>=hits.peekFirst().first&&nowMillis-hits.peekFirst().first>confirmationWindowMillis)hits.removeFirst()
  val cue=labels.asSequence().filter{it.confidence.isFinite()&&it.confidence>=minimumConfidence}
   .mapNotNull{e->actionFor(e.label)?.let{it to e.confidence}}.maxByOrNull{it.second}
  if(cue!=null){
   lastPositiveAt=nowMillis
   if(hits.peekLast()?.second!=cue.first||hits.isEmpty())hits.addLast(nowMillis to cue.first)
   else if(hits.peekLast().first!=nowMillis)hits.addLast(nowMillis to cue.first)
   while(hits.size>4)hits.removeFirst()
   if(hits.count{it.second==cue.first}>=2){confirmedAction=cue.first;confirmedUntil=nowMillis+activeNoteMillis}
  }
  val action=confirmedAction
  if(action!=null&&nowMillis<=confirmedUntil)return when(action){
   Action.COUGH->"قد تشير تصنيفات بصرية متكررة إلى سعال في المشهد، لكنني لا أستطيع تأكيده أو ربطه بشخص معيّن بثقة."
   Action.SNEEZE->"قد تشير تصنيفات بصرية متكررة إلى عطاس في المشهد، لكنني لا أستطيع تأكيده أو ربطه بشخص معيّن بثقة."
  }
  if(lastPositiveAt!=Long.MIN_VALUE&&nowMillis>=lastPositiveAt&&nowMillis-lastPositiveAt>confirmationWindowMillis)reset()
  return null
 }
 @Synchronized fun reset(){hits.clear();lastPositiveAt=Long.MIN_VALUE;confirmedUntil=Long.MIN_VALUE;confirmedAction=null}
 private fun actionFor(raw:String):Action?{
  val label=raw.trim().lowercase(Locale.ROOT)
  val words=label.split(Regex("[^\\p{L}\\p{N}]+")).filter{it.isNotBlank()}.toSet()
  return when{
   words.any{it in setOf("cough","coughing","coughs")}||label.contains("cough")->Action.COUGH
   words.any{it in setOf("sneeze","sneezing","sneezes")}||label.contains("sneez")->Action.SNEEZE
   else->null
  }
 }
}
