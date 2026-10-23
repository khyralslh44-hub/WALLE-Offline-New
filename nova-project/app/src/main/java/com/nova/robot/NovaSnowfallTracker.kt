package com.nova.robot
import java.util.ArrayDeque
/** Requires repeated snow labels and never equates snow on the ground with active snowfall. */
internal class NovaSnowfallTracker(
    private val confirmationWindowMillis:Long=12_000L,
    private val activeNoteMillis:Long=10_000L
){
 private val hits=ArrayDeque<Long>()
 private var lastEvidenceAt=Long.MIN_VALUE
 private var confirmedUntil=Long.MIN_VALUE
 private var fallingWordSeen=false
 @Synchronized fun observe(labels:List<NovaLabelEvidence>,nowMillis:Long):String?{
  if(lastEvidenceAt!=Long.MIN_VALUE&&nowMillis<lastEvidenceAt)reset()
  while(hits.isNotEmpty()&&nowMillis>=hits.peekFirst()&&nowMillis-hits.peekFirst()>confirmationWindowMillis)hits.removeFirst()
  val cue=labels.asSequence().filter{it.confidence.isFinite()&&it.confidence>=0.48f}.firstOrNull{NovaEnvironmentRecognizer.isSnowCue(it.label)}
  if(cue!=null){
   hits.addLast(nowMillis);lastEvidenceAt=nowMillis
   fallingWordSeen=fallingWordSeen||NovaEnvironmentRecognizer.isExplicitSnowfallCue(cue.label)
   if(hits.size>=2)confirmedUntil=nowMillis+activeNoteMillis
  }
  if(nowMillis<=confirmedUntil)return if(fallingWordSeen)
   "تتكرر مؤشرات بصرية قد تشير إلى تساقط الثلج؛ هذا تقدير من تصنيفات عامة وليس تأكيدًا للطقس."
   else "تتكرر مؤشرات على وجود الثلج في المشهد؛ لا أستطيع الجزم بأنه يتساقط، لأن الثلج قد يكون على الأرض."
  if(lastEvidenceAt!=Long.MIN_VALUE&&nowMillis>=lastEvidenceAt&&nowMillis-lastEvidenceAt>confirmationWindowMillis)reset()
  return null
 }
 @Synchronized fun reset(){hits.clear();lastEvidenceAt=Long.MIN_VALUE;confirmedUntil=Long.MIN_VALUE;fallingWordSeen=false}
}
