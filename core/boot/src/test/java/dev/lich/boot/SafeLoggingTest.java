package dev.lich.boot;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against log4j 2.0-beta9, the version 1.7.10/1.8.9 ship. No other test in this JVM touches log4j,
 * so this one decides which configuration log4j starts with.
 */
class SafeLoggingTest {
    @Test
    void fallbackConfigLoadsFromTheClassPathAndDropsLookups() {
        System.clearProperty("log4j.configurationFile");
        Main.ensureSafeLogging();
        assertEquals("dev/lich/boot/log4j2-safe.xml", System.getProperty("log4j.configurationFile"));

        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        assertNotNull(configuration.getAppenders().get("File"), "the safe config is in use, not log4j's default");

        Filter rootFilter = configuration.getLoggerConfig(LogManager.ROOT_LOGGER_NAME).getFilter();
        Logger logger = context.getLogger("test");
        assertEquals(Filter.Result.DENY, rootFilter.filter(logger, Level.INFO, null, "chat: ${jndi:ldap://127.0.0.1:1/x}"));
        assertEquals(Filter.Result.NEUTRAL, rootFilter.filter(logger, Level.INFO, null, "an ordinary message"));
    }

    @Test
    void sessionLineIsDroppedAfterAReconfigurationToo() {
        if (System.getProperty("log4j.configurationFile") == null) {
            Main.ensureSafeLogging();
        }
        SessionLogFilter.install(new String[]{"--accessToken", "0123456789abcdef0123456789abcdef"});
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Logger logger = context.getLogger("net.minecraft.client.Minecraft");
        assertFalse(logger.isEnabled(Level.INFO, null, "(Session ID is token:0123456789abcdef0123456789abcdef:1234)"));
        assertTrue(logger.isEnabled(Level.INFO, null, "Setting user: Dev"));

        context.reconfigure();
        assertFalse(logger.isEnabled(Level.INFO, null, "(Session ID is token:0123456789abcdef0123456789abcdef:1234)"));
        assertFalse(logger.isEnabled(Level.WARN, null, "retrying with 0123456789abcdef0123456789abcdef"));
        assertTrue(logger.isEnabled(Level.INFO, null, "Setting user: Dev"));
    }
}
