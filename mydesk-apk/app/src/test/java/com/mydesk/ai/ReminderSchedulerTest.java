package com.mydesk.ai;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

public class ReminderSchedulerTest {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static long at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, KST)
                .toInstant()
                .toEpochMilli();
    }

    @Test
    public void limitedDailyStopsAtRequestedCount() {
        long base = at(2026, 9, 20, 9, 0);

        assertEquals(
                at(2026, 9, 21, 9, 0),
                ReminderScheduler.nextOccurrenceAfter(base, "DAILY_LIMITED", 3, base)
        );
        assertEquals(
                at(2026, 9, 22, 9, 0),
                ReminderScheduler.nextOccurrenceAfter(base, "DAILY_LIMITED", 3, at(2026, 9, 21, 9, 0))
        );
        assertEquals(
                0L,
                ReminderScheduler.nextOccurrenceAfter(base, "DAILY_LIMITED", 3, at(2026, 9, 22, 9, 0))
        );
    }

    @Test
    public void limitedDailyClampsToSevenDays() {
        long base = at(2026, 9, 20, 9, 0);
        assertEquals(
                0L,
                ReminderScheduler.nextOccurrenceAfter(base, "DAILY_LIMITED", 99, at(2026, 9, 26, 9, 0))
        );
    }

    @Test
    public void weekdaysSkipWeekend() {
        long friday = at(2026, 9, 18, 9, 0);
        assertEquals(
                at(2026, 9, 21, 9, 0),
                ReminderScheduler.nextOccurrenceAfter(friday, "WEEKDAYS", 0, friday)
        );
    }

    @Test
    public void weeklyKeepsSameLocalTime() {
        long base = at(2026, 9, 20, 9, 30);
        assertEquals(
                at(2026, 9, 27, 9, 30),
                ReminderScheduler.nextOccurrenceAfter(base, "WEEKLY", 0, base)
        );
    }
}
