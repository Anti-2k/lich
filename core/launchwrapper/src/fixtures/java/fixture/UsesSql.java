package fixture;

import java.sql.Timestamp;

/** Uses a class from the java.sql platform module, which a bootstrap-only parent can't see. */
public class UsesSql {
    public static long epoch() {
        return new Timestamp(0L).getTime();
    }
}
