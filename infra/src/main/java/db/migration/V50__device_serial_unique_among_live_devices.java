package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * G-6: an admin-deleted device could never register again. Devices are only soft-deleted, the
 * deleted row keeps its serial, and V4 made {@code serial_number} globally {@code UNIQUE} — so
 * {@code POST /api/devices/register} found no live device, inserted a new row and hit the
 * constraint: a 500, on every boot of that TV box, forever.
 *
 * <p>The serial now has to be unique among <b>live</b> devices only. A partial unique index
 * ({@code WHERE deleted_at IS NULL}) would say that on Postgres, but the H2 test harness cannot
 * build one and would lose the guarantee. Instead a generated {@code live_serial} column holds the
 * serial while the row is live and NULL once it is soft-deleted, and carries the {@code UNIQUE}
 * constraint: NULLs never collide, on Postgres and H2 alike, so both enforce the same rule. The
 * application never writes the column ({@code Device} does not map it).
 *
 * <p><b>Why Java:</b> V4 declared the old constraint inline, so its name is engine-generated
 * ({@code device_serial_number_key} on Postgres, {@code CONSTRAINT_nn} on H2) and plain SQL cannot
 * name it portably. It is looked up in {@code information_schema}, which both engines provide. The
 * generated column needs {@code STORED} on Postgres (the only kind before 18) and must not have it
 * on H2, which rejects the keyword.
 */
public class V50__device_serial_unique_among_live_devices extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        boolean postgres = product != null && product.toLowerCase().contains("postgresql");
        try (Statement stmt = connection.createStatement()) {
            for (String name : singleColumnUniqueConstraintsOnSerial(connection)) {
                stmt.execute("ALTER TABLE device DROP CONSTRAINT \"" + name.replace("\"", "\"\"") + "\"");
            }
            stmt.execute("ALTER TABLE device ADD COLUMN live_serial VARCHAR(100) "
                    + "GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN serial_number END)"
                    + (postgres ? " STORED" : ""));
            stmt.execute("ALTER TABLE device ADD CONSTRAINT uq_device_live_serial UNIQUE (live_serial)");
            // Registration and the external API look devices up by serial_number; the dropped
            // constraint's index served those lookups.
            stmt.execute("CREATE INDEX idx_device_serial_number ON device (serial_number)");
        }
    }

    private static List<String> singleColumnUniqueConstraintsOnSerial(Connection connection) throws SQLException {
        String sql = "SELECT tc.constraint_name "
                + "FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON kcu.constraint_schema = tc.constraint_schema AND kcu.constraint_name = tc.constraint_name "
                + "WHERE tc.constraint_type = 'UNIQUE' AND LOWER(tc.table_name) = 'device' "
                + "  AND tc.table_schema = CURRENT_SCHEMA "
                + "GROUP BY tc.constraint_name "
                + "HAVING COUNT(*) = 1 AND MAX(LOWER(kcu.column_name)) = 'serial_number'";
        var names = new ArrayList<String>();
        try (Statement stmt = connection.createStatement(); var rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("V50: no UNIQUE constraint on device.serial_number to replace");
        }
        return names;
    }
}
