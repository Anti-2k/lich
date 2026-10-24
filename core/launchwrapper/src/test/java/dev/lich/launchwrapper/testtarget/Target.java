package dev.lich.launchwrapper.testtarget;

/**
 * Launch target for LaunchCoreTest. LaunchClassLoader defines its own copy of this class, so it reports
 * back through a system property rather than a static field the test could see.
 */
public class Target {
    public static void main(String[] args) {
        System.setProperty("lich.test.launched", Target.class.getClassLoader().getClass().getName() + "|" + String.join(" ", args));
    }
}
