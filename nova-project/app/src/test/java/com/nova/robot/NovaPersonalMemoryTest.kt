package com.nova.robot

import org.junit.Assert.*
import org.junit.Test

class NovaPersonalMemoryTest {
    @Test fun ordinaryConversationIsNeverAutomaticallyStored() {
        val store = InMemoryNovaMindMemory()
        val personal = NovaPersonalMemory(store)
        assertNull(personal.handle("اليوم كان جميلًا", 1L))
        assertTrue(personal.handle("ماذا تتذكر عني", 2L)!!.contains("لا توجد لديّ"))
    }

    @Test fun explicitRememberCommandStoresOnlyTheChosenFact() {
        val store = InMemoryNovaMindMemory()
        val personal = NovaPersonalMemory(store)
        assertTrue(personal.handle("تذكّري أنني أحب القهوة", 10L)!!.contains("حفظت هذه المعلومة"))
        val reply = NovaPersonalMemory(store).handle("ماذا تتذكر عني", 20L)!!
        assertTrue(reply.contains("أحب القهوة"))
        assertFalse(reply.contains("تذكّري أنني"))
    }

    @Test fun specificAndFullForgetCommandsWork() {
        val store = InMemoryNovaMindMemory()
        val personal = NovaPersonalMemory(store)
        personal.handle("تذكر أنني أحب القهوة", 1L)
        personal.handle("تذكر أنني أفضّل الهدوء", 2L)
        assertTrue(personal.handle("انس أنني أحب القهوة", 3L)!!.contains("نسيت المعلومة"))
        val afterOneDelete = personal.handle("ماذا تتذكر عني", 4L)!!
        assertFalse(afterOneDelete.contains("أحب القهوة"))
        assertTrue(afterOneDelete.contains("أفضّل الهدوء"))
        assertTrue(personal.handle("انس كل ما تتذكره عني", 5L)!!.contains("مسحت كل الملاحظات"))
        assertTrue(personal.handle("ماذا تتذكر عني", 6L)!!.contains("لا توجد لديّ"))
    }
}
