package com.roombrowser.data.repo

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.task.AiTaskPermissions
import com.roombrowser.domain.task.ScheduleKind
import com.roombrowser.domain.task.TaskSchedule
import java.time.DayOfWeek
import org.junit.Test

/**
 * The JSON columns are the whole persisted shape of a task's schedule and
 * permissions. Decoding is deliberately total: a corrupt blob must not crash a
 * background worker, so every malformed input is asserted to fall back to a
 * safe default rather than throw.
 */
class AiTaskCodecTest {

    @Test
    fun a_schedule_survives_a_round_trip_with_every_field_populated() {
        val schedule = TaskSchedule(
            kind = ScheduleKind.WEEKLY,
            intervalMinutes = 45,
            minuteOfDay = 8 * 60 + 30,
            daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
            dayOfMonth = 17,
            quietFromMinute = 22 * 60,
            quietToMinute = 7 * 60
        )
        val decoded = AiTaskCodec.decodeSchedule(AiTaskCodec.encodeSchedule(schedule))
        assertThat(decoded).isEqualTo(schedule)
    }

    @Test
    fun an_interval_schedule_survives_a_round_trip() {
        val schedule = TaskSchedule(kind = ScheduleKind.INTERVAL, intervalMinutes = 15)
        assertThat(AiTaskCodec.decodeSchedule(AiTaskCodec.encodeSchedule(schedule)))
            .isEqualTo(schedule)
    }

    @Test
    fun permissions_survive_a_round_trip() {
        val permissions = AiTaskPermissions(
            allowReadPage = false,
            allowNavigate = true,
            allowInteract = false,
            allowPost = true
        )
        assertThat(AiTaskCodec.decodePermissions(AiTaskCodec.encodePermissions(permissions)))
            .isEqualTo(permissions)
    }

    @Test
    fun a_corrupt_schedule_falls_back_to_a_valid_default_instead_of_throwing() {
        assertThat(AiTaskCodec.decodeSchedule("")).isEqualTo(AiTaskCodec.DEFAULT_SCHEDULE)
        assertThat(AiTaskCodec.decodeSchedule("{")).isEqualTo(AiTaskCodec.DEFAULT_SCHEDULE)
        assertThat(AiTaskCodec.decodeSchedule("""{"kind":"NONSENSE"}"""))
            .isEqualTo(AiTaskCodec.DEFAULT_SCHEDULE)
        assertThat(AiTaskCodec.DEFAULT_SCHEDULE.kind).isEqualTo(ScheduleKind.DAILY)
        assertThat(AiTaskCodec.DEFAULT_SCHEDULE.minuteOfDay).isEqualTo(9 * 60)
    }

    @Test
    fun a_corrupt_permissions_blob_falls_back_to_the_default_grants() {
        assertThat(AiTaskCodec.decodePermissions("")).isEqualTo(AiTaskPermissions.DEFAULT)
        assertThat(AiTaskCodec.decodePermissions("not json")).isEqualTo(AiTaskPermissions.DEFAULT)
    }

    @Test
    fun unknown_keys_written_by_a_newer_build_do_not_break_decoding() {
        val forwardWritten =
            """{"kind":"DAILY","minuteOfDay":540,"someFutureField":true}"""
        assertThat(AiTaskCodec.decodeSchedule(forwardWritten))
            .isEqualTo(TaskSchedule(kind = ScheduleKind.DAILY, minuteOfDay = 540))
    }

    @Test
    fun defaults_are_encoded_so_a_missing_field_never_means_an_unknown_value() {
        val encoded = AiTaskCodec.encodeSchedule(TaskSchedule(kind = ScheduleKind.DAILY))
        assertThat(encoded).contains("intervalMinutes")
    }
}
