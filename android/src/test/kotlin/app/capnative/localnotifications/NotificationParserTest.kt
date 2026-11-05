package app.capnative.localnotifications

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationParserTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = NotificationParser.parseAt("2026-11-05T15:30:45.000Z")

    @Test
    fun parsesImmediateAndDatedNotifications() {
        val immediate = notification(mapOf("id" to 7, "title" to "  Hello  ", "body" to "  "))
        assertEquals(7, immediate.id)
        assertEquals("Hello", immediate.title)
        assertEquals("", immediate.body)
        assertTrue(immediate.plan is FirePlan.Immediate)

        val dated = notification(
            mapOf(
                "id" to 1,
                "title" to "Later",
                "body" to "Body",
                "schedule" to mapOf("at" to "2026-11-05T18:00:00+02:00"),
            ),
        )
        assertEquals("2026-11-05T16:00:00.000Z", dated.schedule?.at)
        assertTrue(dated.plan is FirePlan.Once)
    }

    @Test
    fun buildsRepeatingInterval() {
        val parsed = notification(
            mapOf(
                "id" to 1,
                "title" to "Repeat",
                "body" to "Body",
                "schedule" to mapOf("at" to "2026-11-05T15:32:45.000Z", "repeats" to true, "allowWhileIdle" to true),
            ),
        )
        val plan = parsed.plan as FirePlan.FixedInterval
        assertEquals(120_000L, plan.intervalMs)
        assertTrue(parsed.allowWhileIdle)
    }

    @Test
    fun rejectsInvalidInput() {
        assertEquals("INVALID_NOTIFICATION", code(mapOf("id" to 1.5, "title" to "A", "body" to "B")))
        assertEquals("INVALID_NOTIFICATION", code(mapOf("id" to 1, "title" to "   ", "body" to "B")))
        assertEquals("INVALID_NOTIFICATION", code(mapOf("id" to 1, "title" to "A", "body" to "B", "sound" to "../beep.wav")))
        assertEquals("INVALID_NOTIFICATION", code(mapOf("id" to 1, "title" to "A", "body" to "B", "smallIcon" to "Icon")))
        assertEquals(
            "INVALID_NOTIFICATION",
            code(mapOf("id" to 1, "title" to "A", "body" to "B", "extra" to mapOf("__proto__" to mapOf("admin" to true)))),
        )
        assertEquals("INVALID_SCHEDULE", code(mapOf("id" to 1, "title" to "A", "body" to "B", "schedule" to mapOf("on" to emptyMap<String, Any>()))))
        assertEquals(
            "INVALID_SCHEDULE",
            code(mapOf("id" to 1, "title" to "A", "body" to "B", "schedule" to mapOf("every" to "second", "count" to 59))),
        )
        assertEquals(
            "INVALID_SCHEDULE",
            code(mapOf("id" to 1, "title" to "A", "body" to "B", "schedule" to mapOf("at" to "2026-11-05T15:00:00.000Z"))),
        )
    }

    @Test
    fun parsesChannelAndActions() {
        val channel = NotificationParser.parseChannel(mapOf("id" to "reminders", "name" to " Reminders "))
        assertEquals("reminders", channel.id)
        assertEquals(3, channel.importance)
        assertTrue(channel.vibration)
        val quiet = NotificationParser.parseChannel(mapOf("id" to "quiet", "name" to "Quiet", "importance" to 2))
        assertEquals(false, quiet.vibration)
        val types = NotificationParser.parseActionTypes(
            mapOf("types" to listOf(mapOf("id" to "mail", "actions" to listOf(mapOf("id" to "reply", "title" to "Reply", "input" to true))))),
        )
        assertEquals("reply", types[0].actions[0].id)
        assertTrue(types[0].actions[0].input)
    }

    private fun notification(value: Map<String, Any?>): ValidatedNotification {
        return NotificationParser.parseNotifications(listOf(value), now, utc).first()
    }

    private fun code(value: Map<String, Any?>): String {
        return try {
            notification(value)
            "none"
        } catch (error: NotificationException) {
            error.code
        }
    }
}
