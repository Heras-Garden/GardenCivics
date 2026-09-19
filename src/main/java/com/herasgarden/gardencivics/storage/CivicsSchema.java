package com.herasgarden.gardencivics.storage;

import com.herasgarden.gardencore.api.storage.GardenStorage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class CivicsSchema {
    private CivicsSchema() {
    }

    public static void ensure(GardenStorage storage) throws SQLException {
        try (Connection connection = storage.connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gcv_governments ("
                    + "territory_claim_uuid VARCHAR(36) PRIMARY KEY,"
                    + "organization_uuid VARCHAR(36) NOT NULL UNIQUE,"
                    + "government_type VARCHAR(32) NOT NULL DEFAULT 'COUNCIL',"
                    + "created_by VARCHAR(36) NOT NULL,"
                    + "created_at BIGINT NOT NULL)");
            ensureColumn(connection, "gcv_governments", "government_type",
                    "ALTER TABLE gcv_governments ADD COLUMN government_type VARCHAR(32) NOT NULL DEFAULT 'COUNCIL'");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gcv_citizenship_applications ("
                    + "application_uuid VARCHAR(36) PRIMARY KEY,"
                    + "player_uuid VARCHAR(36) NOT NULL,"
                    + "territory_claim_uuid VARCHAR(36) NOT NULL,"
                    + "message TEXT NOT NULL,"
                    + "status VARCHAR(24) NOT NULL,"
                    + "reviewed_by VARCHAR(36) NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "updated_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gcv_applications_territory "
                    + "ON gcv_citizenship_applications (territory_claim_uuid, status, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gcv_applications_player "
                    + "ON gcv_citizenship_applications (player_uuid, status, created_at)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gcv_treasury_journal ("
                    + "transaction_uuid VARCHAR(36) PRIMARY KEY,"
                    + "organization_uuid VARCHAR(36) NOT NULL,"
                    + "actor_uuid VARCHAR(36) NOT NULL,"
                    + "direction VARCHAR(16) NOT NULL,"
                    + "amount BIGINT NOT NULL,"
                    + "balance_after BIGINT NOT NULL,"
                    + "created_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gcv_treasury_org "
                    + "ON gcv_treasury_journal (organization_uuid, created_at)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gcv_payroll_runs ("
                    + "run_uuid VARCHAR(36) PRIMARY KEY,"
                    + "organization_uuid VARCHAR(36) NOT NULL,"
                    + "period_key VARCHAR(32) NOT NULL,"
                    + "status VARCHAR(24) NOT NULL,"
                    + "total BIGINT NOT NULL,"
                    + "initiated_by VARCHAR(36) NOT NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "completed_at BIGINT NULL)");
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_gcv_payroll_period "
                    + "ON gcv_payroll_runs (organization_uuid, period_key)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gcv_payroll_entries ("
                    + "run_uuid VARCHAR(36) NOT NULL,"
                    + "player_uuid VARCHAR(36) NOT NULL,"
                    + "role_key VARCHAR(32) NOT NULL,"
                    + "amount BIGINT NOT NULL,"
                    + "status VARCHAR(24) NOT NULL,"
                    + "updated_at BIGINT NOT NULL,"
                    + "PRIMARY KEY (run_uuid, player_uuid))");
        }
    }

    private static void ensureColumn(Connection connection, String table, String column, String ddl) throws SQLException {
        try (ResultSet result = connection.getMetaData().getColumns(null, null, table, column)) {
            if (result.next()) return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(ddl);
        }
    }
}
