package com.nova.robot
import org.junit.Assert.*
import org.junit.Test
class NovaSensorAndBehaviorCuesTest{
 @Test fun shakeNeedsSeveralStrongPulsesAndHasCooldown(){
  val d=NovaShakeDetector()
  assertFalse(d.observe(0f,0f,9.8f,100L))
  assertFalse(d.observe(9f,0f,9.8f,300L))
  assertFalse(d.observe(-9f,0f,9.8f,500L))
  assertTrue(d.observe(9f,0f,9.8f,700L))
  assertFalse(d.observe(-9f,0f,9.8f,1_000L))
 }
 @Test fun sustainedClosedEyesOnlySuggestDrowsinessOnce(){
  val t=NovaSleepCueTracker()
  assertNull(t.observe(true,.05f,.08f,1_000L))
  assertNull(t.observe(true,.05f,.08f,4_000L))
  assertNull(t.observe(true,.05f,.08f,7_000L))
  assertTrue(t.observe(true,.05f,.08f,9_000L)?.contains("لا يؤكد أنه نائم")==true)
  assertNull(t.observe(true,.05f,.08f,10_000L))
  assertNull(t.observe(true,.92f,.91f,11_000L))
  assertNull(t.observe(true,.05f,.08f,12_000L))
 }
 @Test fun snowfallRequiresRepeatedLabelsAndRemainsTentative(){
  val t=NovaSnowfallTracker()
  assertNull(t.observe(listOf(NovaLabelEvidence("snow",.85f)),100L))
  val n=t.observe(listOf(NovaLabelEvidence("snowing",.81f)),2_000L)
  assertTrue(n?.contains("قد تشير")==true)
  assertTrue(n?.contains("ليس تأكيدًا")==true)
 }
 @Test fun coughAndSneezeRequireRepeatedLabelsAndFace(){
  val t=NovaTransientActionTracker()
  val cough=listOf(NovaLabelEvidence("coughing person",.86f))
  assertNull(t.observe(cough,true,100L))
  assertTrue(t.observe(cough,true,2_000L)?.contains("لا أستطيع تأكيده")==true)
  t.reset();val sneeze=listOf(NovaLabelEvidence("sneezing",.9f))
  assertNull(t.observe(sneeze,false,3_000L))
  assertNull(t.observe(sneeze,true,4_000L))
  assertTrue(t.observe(sneeze,true,6_000L)?.contains("عطاس")==true)
 }
 @Test fun autonomousNotificationPolicyUsesQuietHoursIdleAndCooldown(){
  val now=100_000_000L;val idle=now-NovaAutonomousNotificationPolicy.MIN_IDLE_MILLIS
  assertTrue(NovaAutonomousNotificationPolicy.shouldNotify(now,idle,0L,10))
  assertFalse(NovaAutonomousNotificationPolicy.shouldNotify(now,idle+1L,0L,10))
  assertFalse(NovaAutonomousNotificationPolicy.shouldNotify(now,idle,0L,23))
  assertFalse(NovaAutonomousNotificationPolicy.shouldNotify(now,idle,now-1_000L,10))
 }
 @Test fun handGestureRequiresTwoConsistentPredictions(){
  val t=NovaHandGestureStabilizer()
  assertNull(t.observe(NovaHandGestureCue("Thumb_Up",.95f),100L))
  assertTrue(t.observe(NovaHandGestureCue("Thumb_Up",.93f),800L)?.contains("إبهام إلى الأعلى")==true)
  assertTrue(t.observe(NovaHandGestureCue("None",.99f),900L)?.contains("إبهام إلى الأعلى")==true)
 }
}
