package db.migration;

import java.sql.Statement;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * AUTH-2: harden every remaining plaintext password.
 *
 * <p>Login verifies with {@code BCryptPasswordEncoder.matches(raw, stored)}, so any row whose
 * {@code password} is not already a BCrypt hash is legacy plaintext that can NEVER log in. V33
 * only converted the exact literal 'password'; admin-created accounts (created before user
 * creation BCrypt-encoded the password) still hold arbitrary plaintext. Hash those in place via
 * pgcrypto so their existing credentials start working without a manual reset. A BCrypt hash
 * always starts with {@code $2a$} / {@code $2b$} / {@code $2y$}; anything else is plaintext.
 * {@code crypt(value, gen_salt('bf', 10))} emits a {@code $2a$10$} hash that
 * {@code BCryptPasswordEncoder} (default strength 10) verifies — the cost matches the V33 seed
 * and the application encoder.
 *
 * <p><b>Why this is a Java migration, not SQL.</b> {@code CREATE EXTENSION pgcrypto} +
 * {@code crypt()}/{@code gen_salt()} are Postgres-only; the H2 test harness (H2 in
 * {@code MODE=PostgreSQL}) cannot even parse {@code CREATE EXTENSION} (SQL state 42001), which
 * would abort the whole migration chain under H2. Gating by database product keeps the exact
 * Postgres behavior in prod while making the migration a clean no-op on H2 — where there is no
 * legacy plaintext anyway (the dev/test seed in V33 is already a BCrypt hash, so the {@code WHERE}
 * filter would match nothing even if pgcrypto existed).
 */
public class V36__bcrypt_existing_plaintext_passwords extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        // H2 (incl. MODE=PostgreSQL) reports product name "H2"; only real Postgres reports
        // "PostgreSQL". pgcrypto is unavailable on H2, so skip — nothing to hash there.
        String product = connection.getMetaData().getDatabaseProductName();
        if (product == null || !product.toLowerCase().contains("postgresql")) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
            stmt.execute(
                    "UPDATE app_user " +
                    "   SET password = crypt(password, gen_salt('bf', 10)) " +
                    " WHERE password NOT LIKE '$2a$%' " +
                    "   AND password NOT LIKE '$2b$%' " +
                    "   AND password NOT LIKE '$2y$%'");
        }
    }
}
