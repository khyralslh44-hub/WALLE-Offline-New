package com.eve.robot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class EveTravelMemoryTest {
 @Test fun recordsExplicitPastTravelAndQualifiesItsSource(){
  val m=EveTravelMemory();val f=m.recordStatement("إيف، أنا سافرت إلى تركيا في الصيف",1000L)
  assertNotNull(f);assertEquals(EveTravelFactType.TRAVEL_HISTORY,f!!.type)
  val a=m.answerQuestion("هل سبق وسافرت؟")!!;assertTrue(a.contains("تركيا"));assertTrue(a.contains("ليست تحققًا مستقلًا"))
 }
 @Test fun recordsClearlyExpressedTravelDesire(){
  val m=EveTravelMemory();val f=m.recordStatement("إيف، أريد السفر إلى اليابان",2000L)
  assertNotNull(f);assertEquals(EveTravelFactType.TRAVEL_INTENTION,f!!.type);assertTrue(m.answerQuestion("هل ترغب بالسفر؟")!!.contains("اليابان"))
 }
 @Test fun thirdPartyTravelIsIgnoredWithoutExplicitSave(){
  assertNull(EveTravelMemory().recordStatement("أخي سافر إلى إسبانيا",3000L))
 }
 @Test fun explicitSaveCanStoreThirdPartyTravel(){
  val m=EveTravelMemory();val f=m.recordStatement("إيف احفظي أن أخي سافر إلى إسبانيا",4000L)
  assertNotNull(f);assertEquals(EveTravelFactType.TRAVEL_HISTORY,f!!.type);assertTrue(m.answerQuestion("هل سبق وسافرت؟")!!.contains("إسبانيا"))
 }
 @Test fun recordsSurviveSerializationAndCanBeDeleted(){
  val m=EveTravelMemory();m.recordStatement("أنا سافرت إلى مصر",100L);m.recordStatement("أنا أريد السفر إلى كندا",200L)
  val r=EveTravelMemory(m.serialize());assertTrue(r.answerQuestion("هل سافرت من قبل؟")!!.contains("مصر"))
  assertTrue(r.answerQuestion("هل تريد السفر؟")!!.contains("كندا"));r.clear()
  assertTrue(r.answerQuestion("هل سافرت من قبل؟")!!.contains("لا أملك ذاكرة صريحة"))
 }
 @Test fun unrelatedWordsDoNotCreateTravelMemory(){
  val m=EveTravelMemory();assertNull(m.recordStatement("أنا أحب مشاهدة الأفلام",100L));assertNull(m.answerQuestion("ما اسمك؟"))
 }
}
