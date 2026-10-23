package com.nova.robot
import java.util.Locale
internal data class NovaHandGestureCue(val label:String,val confidence:Float)
/** Stabilizes MediaPipe's canned gesture labels across two camera analyses. */
internal class NovaHandGestureStabilizer(
 private val minimumConfidence:Float=0.68f,
 private val confirmationWindowMillis:Long=12_000L,
 private val activeNoteMillis:Long=8_000L
){
 private var candidate:String?=null
 private var candidateCount=0
 private var candidateAt=Long.MIN_VALUE
 private var activeLabel:String?=null
 private var activeUntil=Long.MIN_VALUE
 @Synchronized fun observe(cue:NovaHandGestureCue?,nowMillis:Long):String?{
  if(candidateAt!=Long.MIN_VALUE&&nowMillis<candidateAt)reset()
  val label=cue?.label?.trim()?.lowercase(Locale.ROOT)
  val confidence=cue?.confidence?:0f
  if(label.isNullOrBlank()||label=="none"||!confidence.isFinite()||confidence<minimumConfidence){
   if(candidateAt!=Long.MIN_VALUE&&nowMillis>=candidateAt&&nowMillis-candidateAt>confirmationWindowMillis){candidate=null;candidateCount=0;candidateAt=Long.MIN_VALUE}
   return currentNote(nowMillis)
  }
  if(candidate==label&&candidateAt!=Long.MIN_VALUE&&nowMillis>=candidateAt&&nowMillis-candidateAt in 300L..confirmationWindowMillis)candidateCount++
  else{candidate=label;candidateCount=1}
  candidateAt=nowMillis
  if(candidateCount>=2){activeLabel=label;activeUntil=nowMillis+activeNoteMillis;candidate=null;candidateCount=0;candidateAt=Long.MIN_VALUE}
  return currentNote(nowMillis)
 }
 @Synchronized fun currentNote(nowMillis:Long):String?{
  if(nowMillis>activeUntil){activeLabel=null;activeUntil=Long.MIN_VALUE;return null}
  val label=activeLabel?:return null
  val message=when(label){
   "open_palm"->"أظن أنك تلوّح لي بكف مفتوح."
   "closed_fist"->"ألاحظ إشارة قبضة اليد."
   "pointing_up"->"أظن أنك تشير بإصبعك إلى الأعلى."
   "thumb_up"->"أظن أن هذه إشارة إبهام إلى الأعلى."
   "thumb_down"->"أظن أن هذه إشارة إبهام إلى الأسفل."
   "victory"->"أظن أنك تشير بإصبعي النصر."
   "iloveyou"->"قد تكون هذه إشارة «أحبك» في لغة الإشارة الأمريكية؛ قد يختلف المعنى حسب السياق."
   else->return null
  }
  return message+" (تعرّف بصري تقريبي؛ يمكنك تصحيحي إن أخطأت.)"
 }
 @Synchronized fun reset(){candidate=null;candidateCount=0;candidateAt=Long.MIN_VALUE;activeLabel=null;activeUntil=Long.MIN_VALUE}
}
