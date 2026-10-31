package dev.lich.boot;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The filter on its own; {@link SafeLoggingTest} installs it in log4j. */
class SessionLogFilterTest {
    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJ4dWlkIjoiMjUzNSJ9.c2lnbmF0dXJl";

    @Test
    void readsTheAccessTokenInEitherSpelling() {
        assertEquals(TOKEN, SessionLogFilter.accessToken(new String[]{"--username", "Dev", "--accessToken", TOKEN}));
        assertEquals(TOKEN, SessionLogFilter.accessToken(new String[]{"--accessToken=" + TOKEN}));
        assertNull(SessionLogFilter.accessToken(new String[]{"--username", "Dev", "--accessToken"}));
    }

    @Test
    void dropsTheSessionLineAndAnyLineWithTheToken() {
        SessionLogFilter filter = new SessionLogFilter(TOKEN);
        assertEquals(Filter.Result.DENY, filter.filter(null, Level.INFO, null, "(Session ID is token:" + TOKEN + ":380df991)"));
        assertEquals(Filter.Result.DENY, filter.filter(null, Level.INFO, null, "auth header Bearer " + TOKEN));
        assertEquals(Filter.Result.DENY, filter.filter(null, Level.DEBUG, null, "token {} for {}", TOKEN, "Dev"));
        assertEquals(Filter.Result.DENY, filter.filter(null, Level.INFO, null, (Object) ("x" + TOKEN), null));
        assertEquals(Filter.Result.DENY, filter.filter(null, Level.INFO, null, new ParameterizedMessage("got {}", TOKEN), null));
        assertEquals(Filter.Result.DENY, filter.filter(null, Level.INFO, null, new SimpleMessage("(Session ID is token:0:1)"), null));
        assertEquals(Filter.Result.NEUTRAL, filter.filter(null, Level.INFO, null, "Setting user: Dev"));
        assertEquals(Filter.Result.NEUTRAL, filter.filter(null, Level.INFO, null, "{} loaded", 42));
    }

    @Test
    void ignoresOfflinePlaceholderTokens() {
        SessionLogFilter filter = new SessionLogFilter("0");
        assertEquals(Filter.Result.NEUTRAL, filter.filter(null, Level.INFO, null, "Loaded 10 textures"));
        assertEquals(Filter.Result.DENY, filter.filter(null, Level.INFO, null, "(Session ID is token:0:380df991)"));
    }
}
