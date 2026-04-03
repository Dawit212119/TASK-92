package com.civicworks.unit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema-regression guard: verifies that every column mapped by key JPA entities
 * is present in Flyway migrations (CREATE TABLE or ALTER TABLE ... ADD COLUMN).
 *
 * <p>This prevents the common failure mode where a new {@code @Column} is added to
 * an entity but no migration is written, causing Hibernate {@code validate} mode
 * to abort at startup.
 */
class SchemaCompatibilityTest {

    private static final Path MIGRATION_DIR = resolveRepoMigrationDir();

    /** All column names found per table across all migration files. */
    private static final Map<String, Set<String>> TABLE_COLUMNS = new HashMap<>();

    @BeforeAll
    static void parseMigrations() throws IOException {
        List<Path> sqlFiles = Files.list(MIGRATION_DIR)
                .filter(p -> p.toString().endsWith(".sql"))
                .sorted()
                .toList();

        // Pattern for CREATE TABLE: captures table name + body
        Pattern createTable = Pattern.compile(
                "(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)\\s*\\(([^;]+)\\);");
        // Pattern for ALTER TABLE ... ADD COLUMN [IF NOT EXISTS]
        Pattern alterAddCol = Pattern.compile(
                "(?i)ALTER\\s+TABLE\\s+(\\w+)\\s+ADD\\s+COLUMN\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)");

        for (Path file : sqlFiles) {
            String sql = Files.readString(file);

            Matcher ct = createTable.matcher(sql);
            while (ct.find()) {
                String tableName = ct.group(1).toLowerCase();
                String body = ct.group(2);
                Set<String> cols = TABLE_COLUMNS.computeIfAbsent(tableName, k -> new LinkedHashSet<>());
                extractColumns(body, cols);
            }

            Matcher ac = alterAddCol.matcher(sql);
            while (ac.find()) {
                String tableName = ac.group(1).toLowerCase();
                String colName = ac.group(2).toLowerCase();
                TABLE_COLUMNS.computeIfAbsent(tableName, k -> new LinkedHashSet<>()).add(colName);
            }
        }
    }

    // ── billing_runs ─────────────────────────────────────────────────────

    @Test
    void billingRuns_hasAllMappedColumns() {
        assertTableHasColumns("billing_runs",
                "id", "cycle_date", "billing_cycle", "status",
                "idempotency_key", "requested_by", "organization_id",
                "created_at", "updated_at");
    }

    // ── bills ────────────────────────────────────────────────────────────

    @Test
    void bills_hasAllMappedColumns() {
        assertTableHasColumns("bills",
                "id", "account_id", "billing_run_id", "cycle_date", "due_date",
                "amount_cents", "balance_cents", "status", "version",
                "organization_id", "created_at", "updated_at");
    }

    // ── dispatch_orders ──────────────────────────────────────────────────

    @Test
    void dispatchOrders_hasAllMappedColumns() {
        assertTableHasColumns("dispatch_orders",
                "id", "zone_id", "mode", "status",
                "pickup_lat", "pickup_lng",
                "forced_flag", "assigned_driver_id", "assigned_at",
                "organization_id", "version",
                "created_at", "updated_at");
    }

    // ── audit_logs ───────────────────────────────────────────────────────

    @Test
    void auditLogs_hasAllMappedColumns() {
        assertTableHasColumns("audit_logs",
                "id", "actor_id", "action", "entity_ref",
                "organization_id", "created_at");
    }

    // ── discrepancy_cases ────────────────────────────────────────────────

    @Test
    void discrepancyCases_hasAllMappedColumns() {
        assertTableHasColumns("discrepancy_cases",
                "id", "handover_id", "delta_cents",
                "status", "organization_id",
                "created_at", "updated_at");
    }

    // ── helper methods ───────────────────────────────────────────────────

    private void assertTableHasColumns(String table, String... expectedColumns) {
        Set<String> actual = TABLE_COLUMNS.getOrDefault(table, Set.of());
        assertThat(actual)
                .as("Migration-defined columns for table '%s'", table)
                .containsAll(List.of(expectedColumns));
    }

    /**
     * Extracts column names from a CREATE TABLE body. Skips constraints and
     * lines that start with keywords like PRIMARY, UNIQUE, FOREIGN, CHECK, CONSTRAINT.
     */
    private static void extractColumns(String body, Set<String> target) {
        for (String line : body.split(",")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // Skip constraint lines
            String upper = trimmed.toUpperCase();
            if (upper.startsWith("PRIMARY") || upper.startsWith("UNIQUE") ||
                upper.startsWith("FOREIGN") || upper.startsWith("CHECK") ||
                upper.startsWith("CONSTRAINT") || upper.startsWith("EXCLUDE")) {
                continue;
            }
            // First word is the column name
            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length >= 2) {
                String col = parts[0].toLowerCase().replaceAll("[\"'`]", "");
                if (!col.isEmpty() && col.matches("[a-z_][a-z0-9_]*")) {
                    target.add(col);
                }
            }
        }
    }

    private static Path resolveRepoMigrationDir() {
        // unit_tests module is at repo/unit_tests; migrations are at repo/src/main/resources/db/migration
        Path unitTestDir = Paths.get(System.getProperty("user.dir"));
        Path repoRoot = unitTestDir.getParent();
        if (repoRoot == null) repoRoot = unitTestDir;
        Path migDir = repoRoot.resolve("src/main/resources/db/migration");
        if (!Files.isDirectory(migDir)) {
            // Fallback: maybe we're running from repo root
            migDir = unitTestDir.resolve("src/main/resources/db/migration");
        }
        return migDir;
    }
}
