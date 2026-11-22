package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaytimeTest {
    @Test
    void formatsZeroTicks() {
        assertEquals("0h 00m 00s", Playtime.format(0));
    }

    @Test
    void formatsMinutesAndSeconds() {
        assertEquals("0h 01m 30s", Playtime.format(90 * 20));
    }

    @Test
    void formatsHoursBeyond24WithoutWrapping() {
        int ticks = (27 * 3600 + 15 * 60 + 30) * 20;
        assertEquals("27h 15m 30s", Playtime.format(ticks));
    }

    @Test
    void formatsTripleDigitHours() {
        int ticks = (120 * 3600 + 5 * 60 + 9) * 20;
        assertEquals("120h 05m 09s", Playtime.format(ticks));
    }
}
