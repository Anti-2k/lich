package dev.lich.boot;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.apache.logging.log4j.core.selector.ClassLoaderContextSelector;
import org.apache.logging.log4j.core.selector.ContextSelector;
import org.apache.logging.log4j.message.Message;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Keeps the access token out of the game log. Vanilla 1.7.10 and 1.8.9 log {@code (Session ID is
 * token:<access token>:<uuid>)} at startup, in plain text in {@code logs/latest.log}, which users paste into support
 * chats; Forge's patched {@code Main} drops the line, and so does this filter, whatever the launcher's log4j config.
 * It also drops any other line that contains the token itself, if it's long enough to be a real one (offline
 * launches pass {@code 0}). A filter can only drop a line, not rewrite it.
 *
 * <p>It's the context-wide filter, which log4j asks before a logger's level, so it sees every message: it only looks
 * at the raw text and string parameters, and never formats a message. Public for log4j, which creates the
 * {@link Selector}.
 */
public final class SessionLogFilter extends AbstractFilter {
    /** How the game words the line, in both versions. */
    static final String SESSION_LINE = "(Session ID is ";
    /** Shorter tokens are offline placeholders: matching them would drop ordinary lines. */
    private static final int MIN_TOKEN_LENGTH = 16;

    private final String token;

    SessionLogFilter(String token) {
        super(Result.DENY, Result.NEUTRAL);
        this.token = token != null && token.length() >= MIN_TOKEN_LENGTH ? token : null;
    }

    /**
     * Puts the filter in every log4j context, present and future. There is more than one: log4j keeps a context per
     * class loader, and LaunchClassLoader (whose parent is {@code null}) gets its own, which the game logs through.
     * So unless log4j is already running, {@link Selector} wraps the context selector the launcher asked for (or
     * log4j's default) and adds the filter to each context it hands out.
     */
    static void install(String[] args) {
        filter = new SessionLogFilter(accessToken(args));
        String selector = System.getProperty(SELECTOR_PROPERTY);
        if (!Selector.class.getName().equals(selector)) {
            System.setProperty(DELEGATE_PROPERTY, selector == null ? ClassLoaderContextSelector.class.getName() : selector);
            System.setProperty(SELECTOR_PROPERTY, Selector.class.getName());
        }
        // Read the property now, or find log4j already running (a relaunch): then at least the contexts it has.
        if (LogManager.getFactory() instanceof Log4jContextFactory factory && !(factory.getSelector() instanceof Selector)) {
            factory.getSelector().getLoggerContexts().forEach(SessionLogFilter::addTo);
        }
    }

    private static final String SELECTOR_PROPERTY = "Log4jContextSelector";
    private static final String DELEGATE_PROPERTY = "lich.log4jContextSelector";
    private static final Set<LoggerContext> FILTERED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static volatile SessionLogFilter filter;

    /**
     * Adds the filter to a context's configuration, and to every later one: starting or reconfiguring a context
     * replaces its configuration, filters and all.
     */
    static void addTo(LoggerContext context) {
        SessionLogFilter filter = SessionLogFilter.filter;
        if (filter == null || !FILTERED.add(context)) {
            return;
        }
        context.addPropertyChangeListener(event -> {
            if (LoggerContext.PROPERTY_CONFIG.equals(event.getPropertyName())
                    && event.getNewValue() instanceof Configuration configuration) {
                configuration.addFilter(filter);
            }
        });
        context.addFilter(filter);
    }

    /** log4j's context selector (named by {@code Log4jContextSelector}), with the filter in every context it gives. */
    public static final class Selector implements ContextSelector {
        private final ContextSelector delegate;

        public Selector() throws ReflectiveOperationException {
            String name = System.getProperty(DELEGATE_PROPERTY, ClassLoaderContextSelector.class.getName());
            delegate = (ContextSelector) Class.forName(name, true, Selector.class.getClassLoader())
                    .getConstructor().newInstance();
        }

        @Override
        public LoggerContext getContext(String fqcn, ClassLoader loader, boolean currentContext) {
            LoggerContext context = delegate.getContext(fqcn, loader, currentContext);
            addTo(context);
            return context;
        }

        @Override
        public LoggerContext getContext(String fqcn, ClassLoader loader, boolean currentContext, URI configLocation) {
            LoggerContext context = delegate.getContext(fqcn, loader, currentContext, configLocation);
            addTo(context);
            return context;
        }

        @Override
        public List<LoggerContext> getLoggerContexts() {
            return delegate.getLoggerContexts();
        }

        @Override
        public void removeContext(LoggerContext context) {
            delegate.removeContext(context);
        }
    }

    /** The {@code --accessToken} launch argument, in either spelling, or {@code null}. */
    static String accessToken(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--accessToken") && i + 1 < args.length) {
                return args[i + 1];
            } else if (args[i].startsWith("--accessToken=")) {
                return args[i].substring("--accessToken=".length());
            }
        }
        return null;
    }

    boolean leaks(CharSequence text) {
        if (text == null) {
            return false;
        }
        String string = text.toString();
        return string.contains(SESSION_LINE) || token != null && string.contains(token);
    }

    private boolean leaks(String text, Object... params) {
        if (leaks(text)) {
            return true;
        }
        if (params != null) {
            for (Object param : params) {
                // Only strings: another object's toString could be costly, or log itself.
                if (param instanceof CharSequence sequence && leaks(sequence)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Result result(boolean leaks) {
        return leaks ? onMatch : onMismatch;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        return result(leaks(msg, params));
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        return result(msg instanceof CharSequence sequence && leaks(sequence));
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        return result(msg != null && leaks(msg.getFormat(), msg.getParameters()));
    }

    @Override
    public Result filter(LogEvent event) {
        Message message = event.getMessage();
        return result(message != null && leaks(message.getFormat(), message.getParameters()));
    }
}
