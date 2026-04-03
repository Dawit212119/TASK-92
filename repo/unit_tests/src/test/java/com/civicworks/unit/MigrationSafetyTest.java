package com.civicworks.unit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static guard: asserts that the Flyway migration scripts do not define
 * {@code notification_outbox} more than once.  A duplicate CREATE TABLE
 * across migrations causes startup failures on fresh databases.
 *
 * <p>This test reads the SQL files directly from disk and counts occurrences
 * of the pattern.  No database connection is required.
 */
class MigrationSafetyTest {

    private static final Path MIGRATION_DIR = Paths.get(
            System.getProperty("user.dir"),
            "src", "main", "resources", "db", "migration");

    private static final Pattern CREATE_TABLE_OUTBOX =
            Pattern.compile("(?i)create\\s+table\\s+(if\\s+not\\s+exists\\s+)?notification_outbox");

    @Test
    void notification_outbox_defined_in_exactly_one_migration() throws IOException {
        List<Path> sqlFiles = Files.list(MIGRATION_DIR)
                .filter(p -> p.toString().endsWith(".sql"))
                .sorted()
                .toList();

        assertThat(sqlFiles)
                .as("Expected at least one SQL migration file in %s", MIGRATION_DIR)
                .isNotEmpty();

        int totalMatches = 0;
        Path definingFile = null;

        for (Path file : sqlFiles) {
            String content = Files.readString(file);
            long count = CREATE_TABLE_OUTBOX.matcher(content).results().count();
            if (count > 0) {
                totalMatches += count;
                definingFile = file;
            }
        }

        assertThat(totalMatches)
                .as("CREATE TABLE notification_outbox should appear in exactly one migration file "
                        + "(found %d occurrences across %s); "
                        + "duplicate DDL causes Flyway startup failures on fresh databases.",
                        totalMatches, sqlFiles)
                .isEqualTo(1);

        assertThat(definingFile.getFileName().toString())
                .as("notification_outbox should be defined in V3__extensions.sql (the authoritative source)")
                .isEqualTo("V3__extensions.sql");
    }

    @Test
    void v1_migration_does_not_create_notification_outbox() throws IOException {
        Path v1 = MIGRATION_DIR.resolve("V1__initial_schema.sql");
        assertThat(v1).exists();

        String content = Files.readString(v1);
        assertThat(CREATE_TABLE_OUTBOX.matcher(content).find())
                .as("V1__initial_schema.sql must not contain CREATE TABLE notification_outbox "
                        + "— the table is defined in V3__extensions.sql")
                .isFalse();
    }
}
