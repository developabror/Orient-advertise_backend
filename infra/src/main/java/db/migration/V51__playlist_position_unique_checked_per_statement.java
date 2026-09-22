package db.migration;

import java.sql.Statement;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * LOGIC-09: every playlist edit that shifts items ({@code UPDATE playlist_item SET position =
 * position ± 1 WHERE …}) could fail with a duplicate-key error. Postgres checks a plain
 * {@code UNIQUE} constraint row by row as each row is updated, so whether the shift collides
 * depends on the physical order it visits the rows in — and after a reorder that order no longer
 * follows {@code position}. Reorder [1,2,3] → [2,3,1], then remove an item: 409/500.
 *
 * <p>Declaring the constraint {@code DEFERRABLE INITIALLY IMMEDIATE} makes Postgres check it once
 * at the end of each statement, which is what the SQL standard specifies, so a shift is judged on
 * its result. Nothing is deferred to commit, and every shift query stays as it is.
 *
 * <p><b>Why Java:</b> H2 (the test harness) cannot declare a deferrable constraint, and does not
 * need one — it already checks uniqueness per statement, which is also why the tests never saw
 * this. The migration is a no-op there, like V36.
 */
public class V51__playlist_position_unique_checked_per_statement extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        if (product == null || !product.toLowerCase().contains("postgresql")) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE playlist_item "
                    + "DROP CONSTRAINT uq_playlist_position, "
                    + "ADD CONSTRAINT uq_playlist_position UNIQUE (playlist_id, position) "
                    + "DEFERRABLE INITIALLY IMMEDIATE");
        }
    }
}
