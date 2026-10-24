package org.lwjgl.opengl;

/**
 * LWJGL 2's request for a specific OpenGL context (version, profile, debug). {@link Display} turns it
 * into GLFW context hints; without one, it asks for a legacy context, as LWJGL 2 did.
 */
public final class ContextAttribs {
    public static final int CONTEXT_MAJOR_VERSION_ARB = 0x2091;
    public static final int CONTEXT_MINOR_VERSION_ARB = 0x2092;
    public static final int CONTEXT_PROFILE_MASK_ARB = 0x9126;
    public static final int CONTEXT_CORE_PROFILE_BIT_ARB = 0x0001;
    public static final int CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB = 0x0002;
    public static final int CONTEXT_ES2_PROFILE_BIT_EXT = 0x0004;
    public static final int CONTEXT_FLAGS_ARB = 0x2094;
    public static final int CONTEXT_DEBUG_BIT_ARB = 0x0001;
    public static final int CONTEXT_FORWARD_COMPATIBLE_BIT_ARB = 0x0002;
    public static final int CONTEXT_ROBUST_ACCESS_BIT_ARB = 0x00000004;
    public static final int CONTEXT_RESET_ISOLATION_BIT_ARB = 0x00000008;
    public static final int CONTEXT_RESET_NOTIFICATION_STRATEGY_ARB = 0x8256;
    public static final int NO_RESET_NOTIFICATION_ARB = 0x8261;
    public static final int LOSE_CONTEXT_ON_RESET_ARB = 0x8252;
    public static final int CONTEXT_RELEASE_BEHABIOR_ARB = 0x2097;
    public static final int CONTEXT_RELEASE_BEHAVIOR_NONE_ARB = 0x0000;
    public static final int CONTEXT_RELEASE_BEHAVIOR_FLUSH_ARB = 0x2098;
    public static final int CONTEXT_LAYER_PLANE_ARB = 0x2093;

    private final int majorVersion;
    private final int minorVersion;
    private int profileMask;
    private int contextFlags;
    private int contextResetNotificationStrategy = NO_RESET_NOTIFICATION_ARB;
    private int contextReleaseBehavior = CONTEXT_RELEASE_BEHAVIOR_FLUSH_ARB;
    private int layerPlane;

    public ContextAttribs() {
        this(1, 0);
    }

    public ContextAttribs(int majorVersion, int minorVersion) {
        this(majorVersion, minorVersion, 0);
    }

    public ContextAttribs(int majorVersion, int minorVersion, int profileMask) {
        this(majorVersion, minorVersion, profileMask, 0);
    }

    public ContextAttribs(int majorVersion, int minorVersion, int profileMask, int contextFlags) {
        if (majorVersion < 0 || majorVersion > 4 || minorVersion < 0
                || (majorVersion == 4 && minorVersion > 5) || (majorVersion == 3 && minorVersion > 3)
                || (majorVersion == 2 && minorVersion > 1) || (majorVersion == 1 && minorVersion > 5)) {
            throw new IllegalArgumentException("Invalid OpenGL version specified: " + majorVersion + '.' + minorVersion);
        }
        this.majorVersion = majorVersion;
        this.minorVersion = minorVersion;
        this.profileMask = profileMask;
        this.contextFlags = contextFlags;
    }

    private ContextAttribs copy() {
        ContextAttribs copy = new ContextAttribs(majorVersion, minorVersion, profileMask, contextFlags);
        copy.contextResetNotificationStrategy = contextResetNotificationStrategy;
        copy.contextReleaseBehavior = contextReleaseBehavior;
        copy.layerPlane = layerPlane;
        return copy;
    }

    public int getMajorVersion() {
        return majorVersion;
    }

    public int getMinorVersion() {
        return minorVersion;
    }

    public int getProfileMask() {
        return profileMask;
    }

    public boolean isProfileCore() {
        return (profileMask & CONTEXT_CORE_PROFILE_BIT_ARB) != 0;
    }

    public boolean isProfileCompatibility() {
        return (profileMask & CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB) != 0;
    }

    public boolean isProfileES() {
        return (profileMask & CONTEXT_ES2_PROFILE_BIT_EXT) != 0;
    }

    public int getContextFlags() {
        return contextFlags;
    }

    public boolean isDebug() {
        return (contextFlags & CONTEXT_DEBUG_BIT_ARB) != 0;
    }

    public boolean isForwardCompatible() {
        return (contextFlags & CONTEXT_FORWARD_COMPATIBLE_BIT_ARB) != 0;
    }

    public boolean isRobustAccess() {
        return (contextFlags & CONTEXT_ROBUST_ACCESS_BIT_ARB) != 0;
    }

    public boolean isContextResetIsolation() {
        return (contextFlags & CONTEXT_RESET_ISOLATION_BIT_ARB) != 0;
    }

    public int getContextResetNotificationStrategy() {
        return contextResetNotificationStrategy;
    }

    public boolean isLoseContextOnReset() {
        return contextResetNotificationStrategy == LOSE_CONTEXT_ON_RESET_ARB;
    }

    public int getContextReleaseBehavior() {
        return contextReleaseBehavior;
    }

    public int getLayerPlane() {
        return layerPlane;
    }

    private ContextAttribs withProfile(int bit, boolean on, int exclusive) {
        ContextAttribs attribs = copy();
        attribs.profileMask = on ? (profileMask & ~exclusive) | bit : profileMask & ~bit;
        return attribs;
    }

    private ContextAttribs withFlag(int bit, boolean on) {
        ContextAttribs attribs = copy();
        attribs.contextFlags = on ? contextFlags | bit : contextFlags & ~bit;
        return attribs;
    }

    private void checkProfilesSupported() {
        if (majorVersion < 3 || (majorVersion == 3 && minorVersion < 2)) {
            throw new IllegalArgumentException("Profiles are only supported on OpenGL version 3.2 or higher.");
        }
    }

    public ContextAttribs withProfileCore(boolean profileCore) {
        checkProfilesSupported();
        return withProfile(CONTEXT_CORE_PROFILE_BIT_ARB, profileCore, CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB);
    }

    public ContextAttribs withProfileCompatibility(boolean profileCompatibility) {
        checkProfilesSupported();
        return withProfile(CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB, profileCompatibility, CONTEXT_CORE_PROFILE_BIT_ARB);
    }

    public ContextAttribs withProfileES(boolean profileES) {
        if (!(majorVersion == 2 && minorVersion == 0)) {
            throw new IllegalArgumentException("The OpenGL ES profile is only supported on OpenGL version 2.0.");
        }
        return withProfile(CONTEXT_ES2_PROFILE_BIT_EXT, profileES, 0);
    }

    public ContextAttribs withDebug(boolean debug) {
        return withFlag(CONTEXT_DEBUG_BIT_ARB, debug);
    }

    public ContextAttribs withForwardCompatible(boolean forwardCompatible) {
        return withFlag(CONTEXT_FORWARD_COMPATIBLE_BIT_ARB, forwardCompatible);
    }

    public ContextAttribs withRobustAccess(boolean robustAccess) {
        return withFlag(CONTEXT_ROBUST_ACCESS_BIT_ARB, robustAccess);
    }

    public ContextAttribs withContextResetIsolation(boolean contextResetIsolation) {
        return withFlag(CONTEXT_RESET_ISOLATION_BIT_ARB, contextResetIsolation);
    }

    public ContextAttribs withResetNotificationStrategy(int strategy) {
        if (strategy != NO_RESET_NOTIFICATION_ARB && strategy != LOSE_CONTEXT_ON_RESET_ARB) {
            throw new IllegalArgumentException("Invalid context reset notification strategy specified: 0x"
                    + Integer.toHexString(strategy));
        }
        ContextAttribs attribs = copy();
        attribs.contextResetNotificationStrategy = strategy;
        return attribs;
    }

    public ContextAttribs withLoseContextOnReset(boolean loseContextOnReset) {
        return withResetNotificationStrategy(loseContextOnReset ? LOSE_CONTEXT_ON_RESET_ARB : NO_RESET_NOTIFICATION_ARB);
    }

    public ContextAttribs withContextReleaseBehavior(int behavior) {
        if (behavior != CONTEXT_RELEASE_BEHAVIOR_NONE_ARB && behavior != CONTEXT_RELEASE_BEHAVIOR_FLUSH_ARB) {
            throw new IllegalArgumentException("Invalid context release behavior specified: 0x" + Integer.toHexString(behavior));
        }
        ContextAttribs attribs = copy();
        attribs.contextReleaseBehavior = behavior;
        return attribs;
    }

    public ContextAttribs withLayer(int layerPlane) {
        if (layerPlane < 0) {
            throw new IllegalArgumentException("Invalid layer plane specified: " + layerPlane);
        }
        ContextAttribs attribs = copy();
        attribs.layerPlane = layerPlane;
        return attribs;
    }

    @Override
    public String toString() {
        String profile = isProfileCore() ? "Core" : isProfileCompatibility() ? "Compatibility" : isProfileES() ? "ES" : "None";
        return "ContextAttribs: Version=" + majorVersion + '.' + minorVersion + " - Layer=" + layerPlane
                + " - Debug=" + isDebug() + " - ForwardCompatible=" + isForwardCompatible()
                + " - RobustAccess=" + isRobustAccess() + " - Profile=" + profile;
    }
}
