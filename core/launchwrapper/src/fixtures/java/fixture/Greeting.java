package fixture;

/** Loaded through LaunchClassLoader in tests; a transformer rewrites the string constant. */
public class Greeting {
    public static String text() {
        return "ORIGINAL";
    }
}
