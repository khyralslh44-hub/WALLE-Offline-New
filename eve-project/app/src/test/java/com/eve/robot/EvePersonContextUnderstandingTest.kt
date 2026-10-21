package com.eve.robot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class EvePersonContextUnderstandingTest {
 @Test fun repeatedBentKneePoseSuggestsSittingButNotProof(){
  val e=EvePersonContextUnderstanding(); var r=EvePersonContextSnapshot()
  for(i in 0..4){val t=i*700L;r=e.observe(EvePersonContextEvidence(t,0.9f,t,0.82f,t,nearbySeatConfidence=0.75f))}
  assertTrue(r.sittingConfidence>=0.55f);assertTrue(r.summaryArabic.contains("تقديرًا بصريًا"))
 }
 @Test fun repeatedIndependentPhoneNearEarDetectionsSuggestCall(){
  val e=EvePersonContextUnderstanding(); var r=EvePersonContextSnapshot()
  for(i in 1..4){val t=i*450L;r=e.observe(EvePersonContextEvidence(t,0.9f,objectObservationAtMs=t,phoneConfidence=0.85f,phoneNearEarConfidence=0.82f))}
  assertTrue(r.phoneCallConfidence>=0.55f);assertTrue(r.summaryArabic.contains("لا أستطيع تأكيد وجود اتصال فعلي"))
 }
 @Test fun phoneAndHandInFrontSuggestUseButNotScreenContents(){
  val e=EvePersonContextUnderstanding();var r=EvePersonContextSnapshot()
  for(i in 1..6){val t=i*300L;r=e.observe(EvePersonContextEvidence(t,0.9f,objectObservationAtMs=t,phoneConfidence=0.84f,phoneInFrontConfidence=0.8f,handObservationAtMs=t,handNearPhoneConfidence=0.78f))}
  assertTrue(r.phoneBrowsingConfidence>=0.55f);assertTrue(r.summaryArabic.contains("لا أستطيع معرفة محتوى الشاشة"))
 }
 @Test fun oneObjectDetectionIsNotCountedManyTimes(){
  val e=EvePersonContextUnderstanding();var r=EvePersonContextSnapshot()
  for(i in 0..12)r=e.observe(EvePersonContextEvidence(i*250L,0.9f,objectObservationAtMs=100L,phoneConfidence=0.95f,phoneNearEarConfidence=0.95f))
  assertEquals(0f,r.phoneCallConfidence);assertEquals(0f,r.phoneBrowsingConfidence)
 }
 @Test fun phoneWithoutPositionOrHandEvidenceDoesNotImplyCallOrBrowsing(){
  val e=EvePersonContextUnderstanding();var r=EvePersonContextSnapshot()
  for(i in 1..6){val t=i*350L;r=e.observe(EvePersonContextEvidence(t,0.9f,objectObservationAtMs=t,phoneConfidence=0.9f))}
  assertEquals(0f,r.phoneCallConfidence);assertEquals(0f,r.phoneBrowsingConfidence)
 }
}
