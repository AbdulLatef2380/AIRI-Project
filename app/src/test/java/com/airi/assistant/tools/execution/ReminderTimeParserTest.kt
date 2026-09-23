package com.airi.assistant.tools.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderTimeParserTest {
    @Test
    fun parsesEnglishTimeForms() {
        assertEquals(19 to 30, ReminderTimeParser.parseTime("7:30pm"))
        assertEquals(14 to 5, ReminderTimeParser.parseTime("14:05"))
        assertEquals(12 to 0, ReminderTimeParser.parseTime("noon"))
    }

    @Test
    fun parsesArabicTimeAndDigits() {
        assertEquals(19 to 30, ReminderTimeParser.parseTime("ذكّرني الساعة ٧:٣٠ مساءً"))
        assertEquals(8 to 0, ReminderTimeParser.parseTime("الساعة ۸ صباحا"))
        assertEquals(12 to 0, ReminderTimeParser.parseTime("الظهر"))
    }

    @Test
    fun parsesEnglishAndArabicDurations() {
        assertEquals(5 * 60, ReminderTimeParser.parseDuration("5 minutes"))
        assertEquals(90, ReminderTimeParser.parseDuration("مؤقت ٩٠ ثانية"))
        assertEquals(90 * 60, ReminderTimeParser.parseDuration("ساعة و30 دقيقة"))
        assertNull(ReminderTimeParser.parseDuration("tomorrow"))
    }
}
