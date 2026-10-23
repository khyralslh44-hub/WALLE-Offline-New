package com.nova.robot
import org.junit.Assert.*
import org.junit.Test
class NovaKnowledgeMemoryTest{
 @Test fun explicitlyTaughtAnswerCanBeRetrievedLater(){
  val store=InMemoryNovaMindMemory();val k=NovaKnowledgeMemory(store)
  assertTrue(k.handleCommand("علّميني: ما عاصمة فرنسا؟ = باريس",10L)?.contains("تعلّمت منك محليًا")==true)
  assertTrue(k.answerFor("ما عاصمة فرنسا")?.contains("باريس")==true)
  assertNull(k.answerFor("ما عاصمة إيطاليا؟"))
 }
 @Test fun sameQuestionReplacesAnswerAndCanBeCleared(){
  val store=InMemoryNovaMindMemory();val k=NovaKnowledgeMemory(store)
  k.handleCommand("علّميني: لون السماء؟ = أزرق",1L)
  k.handleCommand("علّميني: لون السماء؟ = أزرق داكن أحيانًا",2L)
  assertEquals(1,k.count())
  assertTrue(k.answerFor("لون السماء؟")?.contains("أزرق داكن أحيانًا")==true)
  assertTrue(k.handleCommand("امسحي ما علمتك إياه",3L)?.contains("مسحت")==true)
  assertNull(k.answerFor("لون السماء؟"))
 }
 @Test fun unrelatedMessageIsNotSaved(){
  val k=NovaKnowledgeMemory(InMemoryNovaMindMemory())
  assertNull(k.handleCommand("أحب الشطرنج",1L))
  assertNull(k.answerFor("أحب الشطرنج"))
 }
}
