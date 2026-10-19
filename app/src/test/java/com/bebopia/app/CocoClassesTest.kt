package com.bebopia.app

import com.bebopia.app.core.vision.CocoClasses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CocoClassesTest {
    private fun first(t: String, hint: String? = null) = CocoClasses.forTarget(t, hint)?.first()

    @Test fun frenchWords() {
        assertEquals("dog", first("le chien noir"))
        assertEquals("person", first("la personne en veste rouge"))
        assertEquals("person", first("moi"))
        assertEquals("car", first("une voiture rouge"))
        assertEquals("backpack", first("mon sac à dos bleu"))
        assertEquals("handbag", first("le sac"))
        assertEquals("cow", first("les vaches"))
        assertEquals("horse", first("chevaux"))
        assertEquals("potted plant", first("la tulipe rouge"))
        assertEquals("bicycle", first("le vélo"))
    }

    @Test fun unknownObjects() {
        assertNull(first("mes clés"))
        assertNull(first("le robot aspirateur"))
    }

    @Test fun aiHintWins() {
        assertEquals("dog", first("Rex", "dog"))
        assertEquals("person", first("le facteur", "person"))
        assertEquals("cow", first("quelque chose", "cow"))
    }

    @Test fun genericOrNot() {
        assertTrue(CocoClasses.isGeneric("un chien"))
        assertTrue(CocoClasses.isGeneric("la voiture"))
        assertFalse(CocoClasses.isGeneric("le chien noir"))
        assertFalse(CocoClasses.isGeneric("la tulipe rouge"))
        assertFalse(CocoClasses.isGeneric("mes clés"))
    }
}
