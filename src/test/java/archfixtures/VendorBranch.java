package archfixtures;

import java.sql.Connection;
import java.sql.SQLException;

/** Violates VENDOR_BRANCHING_ONLY_IN_SQL_DIALECT. */
public class VendorBranch {
    boolean isH2(Connection c) throws SQLException {
        return c.getMetaData().getDatabaseProductName().contains("H2");
    }
}
