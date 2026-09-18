package com.herasgarden.gardencivics.payroll;

import com.herasgarden.gardencivics.CivicsService;
import com.herasgarden.gardencivics.CivicsService.GovernmentContext;
import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.integration.IntegrationEventType;
import com.herasgarden.gardencore.api.organization.OrganizationCapability;
import com.herasgarden.gardencore.api.organization.OrganizationDirectory;
import com.herasgarden.gardencore.api.organization.OrganizationRoleView;
import com.herasgarden.gardencore.api.organization.OrganizationView;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PayrollService {
    private final JavaPlugin plugin;
    private final GardenPlatform platform;
    private final OrganizationDirectory organizations;
    private final CivicsService civics;

    public PayrollService(
            JavaPlugin plugin,
            GardenPlatform platform,
            OrganizationDirectory organizations,
            CivicsService civics
    ) {
        this.plugin = plugin;
        this.platform = platform;
        this.organizations = organizations;
        this.civics = civics;
    }

    public PayrollPreview preview(Player actor) throws SQLException {
        GovernmentContext context = civics.requireActorGovernment(actor);
        requirePayrollPermission(actor, context);

        OrganizationView organization = fresh(context.organization().id());
        List<PayrollRecipient> recipients = recipients(organization);
        long total = total(recipients);
        PayrollRunState existing = existingRun(organization.id(), periodKey()).orElse(null);

        return new PayrollPreview(
                periodKey(),
                organization.id(),
                organization.name(),
                recipients,
                total,
                organization.treasury(),
                existing == null ? null : existing.status()
        );
    }

    public synchronized PayrollResult run(Player actor) throws SQLException {
        GovernmentContext context = civics.requireActorGovernment(actor);
        requirePayrollPermission(actor, context);

        OrganizationView organization = fresh(context.organization().id());
        List<PayrollRecipient> recipients = recipients(organization);
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("No government positions currently have a salary.");
        }

        long total = total(recipients);
        if (organization.treasury() < total) {
            throw new IllegalArgumentException(
                    "The government treasury needs " + (total - organization.treasury())
                            + " more Obols to run payroll.");
        }

        String period = periodKey();
        PayrollRunState existing = existingRun(organization.id(), period).orElse(null);
        UUID runId;
        if (existing == null) {
            runId = createRun(organization.id(), period, total, actor.getUniqueId(), recipients);
        } else if ("FAILED_RETRYABLE".equals(existing.status())) {
            runId = existing.id();
            resetRetryableRun(runId, total, actor.getUniqueId(), recipients);
        } else {
            throw new IllegalArgumentException(switch (existing.status()) {
                case "COMPLETED" -> "Payroll has already been completed for this week.";
                case "FAILED_REVIEW" -> "This week's payroll needs administrator review before any retry.";
                default -> "This week's payroll is already being processed.";
            });
        }

        if (!organizations.debitTreasury(organization.id(), total)) {
            markRun(runId, "FAILED_RETRYABLE", null);
            publishFailure(context, runId, period, total, 0L, "TREASURY_DEBIT_FAILED");
            throw new IllegalArgumentException("The government treasury could not fund payroll.");
        }
        markRun(runId, "PAYING", null);

        List<PayrollRecipient> paid = new ArrayList<>();
        PayrollRecipient failed = null;
        for (PayrollRecipient recipient : recipients) {
            if (!platform.currency().deposit(recipient.playerId(), recipient.amount())) {
                failed = recipient;
                markEntry(runId, recipient.playerId(), "FAILED");
                break;
            }
            paid.add(recipient);
            markEntry(runId, recipient.playerId(), "PAID");
        }

        if (failed != null) {
            return rollbackFailedRun(actor, context, organization.id(), runId, period, total, recipients, paid, failed);
        }

        long balanceAfter = fresh(organization.id()).treasury();
        markRun(runId, "COMPLETED", System.currentTimeMillis());
        journalTreasury(organization.id(), actor.getUniqueId(), total, balanceAfter);
        publishCompleted(context, runId, period, total, recipients.size(), balanceAfter);
        return new PayrollResult(runId, period, recipients.size(), total, balanceAfter, List.copyOf(recipients));
    }

    private PayrollResult rollbackFailedRun(
            Player actor,
            GovernmentContext context,
            UUID organizationId,
            UUID runId,
            String period,
            long total,
            List<PayrollRecipient> recipients,
            List<PayrollRecipient> paid,
            PayrollRecipient failed
    ) throws SQLException {
        long unrecovered = 0L;

        for (PayrollRecipient recipient : paid) {
            if (platform.currency().withdraw(recipient.playerId(), recipient.amount())) {
                markEntry(runId, recipient.playerId(), "RECOVERED");
            } else {
                unrecovered = Math.addExact(unrecovered, recipient.amount());
                markEntry(runId, recipient.playerId(), "UNRECOVERED");
            }
        }

        boolean afterFailure = false;
        for (PayrollRecipient recipient : recipients) {
            if (recipient.playerId().equals(failed.playerId())) {
                afterFailure = true;
                continue;
            }
            if (afterFailure) {
                markEntry(runId, recipient.playerId(), "SKIPPED");
            }
        }

        long restoreAmount = total - unrecovered;
        boolean treasuryRestored = restoreAmount == 0
                || organizations.creditTreasury(organizationId, restoreAmount);

        if (unrecovered == 0 && treasuryRestored) {
            markRun(runId, "FAILED_RETRYABLE", System.currentTimeMillis());
            publishFailure(context, runId, period, total, 0L, "ROLLED_BACK");
            throw new IllegalArgumentException(
                    "Payroll could not pay every official. All changes were rolled back; it can be retried.");
        }

        markRun(runId, "FAILED_REVIEW", System.currentTimeMillis());
        long treasuryAfter = fresh(organizationId).treasury();
        publishFailure(
                context,
                runId,
                period,
                total,
                unrecovered,
                treasuryRestored ? "PARTIAL_PAYOUT" : "TREASURY_ROLLBACK_FAILED"
        );
        plugin.getLogger().severe(
                "Payroll " + runId + " for " + organizationId
                        + " requires admin review. Unrecovered payout: " + unrecovered
                        + ", treasuryRestored=" + treasuryRestored + ".");
        throw new IllegalArgumentException(
                "Payroll partially failed and needs administrator review. Run ID: "
                        + runId.toString().substring(0, 8));
    }

    private List<PayrollRecipient> recipients(OrganizationView organization) {
        List<PayrollRecipient> recipients = new ArrayList<>();
        organization.memberRoles().forEach((playerId, roleKey) -> {
            OrganizationRoleView role = organization.roles().get(roleKey);
            if (role != null && role.salary() > 0) {
                recipients.add(new PayrollRecipient(playerId, role.key(), role.displayName(), role.salary()));
            }
        });
        recipients.sort((a, b) -> {
            int byRole = a.roleKey().compareToIgnoreCase(b.roleKey());
            return byRole != 0 ? byRole : a.playerId().compareTo(b.playerId());
        });
        return List.copyOf(recipients);
    }

    private long total(List<PayrollRecipient> recipients) {
        long total = 0L;
        try {
            for (PayrollRecipient recipient : recipients) {
                total = Math.addExact(total, recipient.amount());
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Government payroll total is too large.");
        }
        return total;
    }

    private UUID createRun(
            UUID organizationId,
            String period,
            long total,
            UUID actorId,
            List<PayrollRecipient> recipients
    ) throws SQLException {
        UUID runId = UUID.randomUUID();
        long now = System.currentTimeMillis();
        try (Connection connection = platform.storage().connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO gcv_payroll_runs "
                                + "(run_uuid, organization_uuid, period_key, status, total, initiated_by, created_at, completed_at) "
                                + "VALUES (?, ?, ?, 'PROCESSING', ?, ?, ?, NULL)")) {
                    statement.setString(1, runId.toString());
                    statement.setString(2, organizationId.toString());
                    statement.setString(3, period);
                    statement.setLong(4, total);
                    statement.setString(5, actorId.toString());
                    statement.setLong(6, now);
                    statement.executeUpdate();
                }
                insertEntries(connection, runId, recipients, now);
                connection.commit();
                return runId;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                if (isDuplicate(exception)) {
                    throw new IllegalArgumentException("Payroll already exists for this week.");
                }
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private void resetRetryableRun(
            UUID runId,
            long total,
            UUID actorId,
            List<PayrollRecipient> recipients
    ) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection connection = platform.storage().connection()) {
            connection.setAutoCommit(false);
            try {
                int changed;
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE gcv_payroll_runs SET status = 'PROCESSING', total = ?, initiated_by = ?, "
                                + "created_at = ?, completed_at = NULL "
                                + "WHERE run_uuid = ? AND status = 'FAILED_RETRYABLE'")) {
                    statement.setLong(1, total);
                    statement.setString(2, actorId.toString());
                    statement.setLong(3, now);
                    statement.setString(4, runId.toString());
                    changed = statement.executeUpdate();
                }
                if (changed != 1) {
                    throw new IllegalArgumentException("This payroll run is no longer retryable.");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM gcv_payroll_entries WHERE run_uuid = ?")) {
                    statement.setString(1, runId.toString());
                    statement.executeUpdate();
                }
                insertEntries(connection, runId, recipients, now);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private void insertEntries(
            Connection connection,
            UUID runId,
            List<PayrollRecipient> recipients,
            long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO gcv_payroll_entries "
                        + "(run_uuid, player_uuid, role_key, amount, status, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PENDING', ?)")) {
            for (PayrollRecipient recipient : recipients) {
                statement.setString(1, runId.toString());
                statement.setString(2, recipient.playerId().toString());
                statement.setString(3, recipient.roleKey());
                statement.setLong(4, recipient.amount());
                statement.setLong(5, now);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private java.util.Optional<PayrollRunState> existingRun(UUID organizationId, String period) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT run_uuid, status FROM gcv_payroll_runs "
                             + "WHERE organization_uuid = ? AND period_key = ? LIMIT 1")) {
            statement.setString(1, organizationId.toString());
            statement.setString(2, period);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? java.util.Optional.of(new PayrollRunState(
                        UUID.fromString(result.getString("run_uuid")),
                        result.getString("status")))
                        : java.util.Optional.empty();
            }
        }
    }

    private void markEntry(UUID runId, UUID playerId, String status) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gcv_payroll_entries SET status = ?, updated_at = ? "
                             + "WHERE run_uuid = ? AND player_uuid = ?")) {
            statement.setString(1, status);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, runId.toString());
            statement.setString(4, playerId.toString());
            statement.executeUpdate();
        }
    }

    private void markRun(UUID runId, String status, Long completedAt) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gcv_payroll_runs SET status = ?, completed_at = ? WHERE run_uuid = ?")) {
            statement.setString(1, status);
            if (completedAt == null) statement.setNull(2, java.sql.Types.BIGINT);
            else statement.setLong(2, completedAt);
            statement.setString(3, runId.toString());
            statement.executeUpdate();
        }
    }

    private void journalTreasury(
            UUID organizationId,
            UUID actorId,
            long amount,
            long balanceAfter
    ) {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO gcv_treasury_journal "
                             + "(transaction_uuid, organization_uuid, actor_uuid, direction, amount, balance_after, created_at) "
                             + "VALUES (?, ?, ?, 'PAYROLL', ?, ?, ?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, organizationId.toString());
            statement.setString(3, actorId.toString());
            statement.setLong(4, amount);
            statement.setLong(5, balanceAfter);
            statement.setLong(6, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning(
                    "Payroll completed, but its treasury journal row could not be written: "
                            + exception.getMessage());
        }
    }

    private void requirePayrollPermission(Player actor, GovernmentContext context) {
        if (actor.hasPermission("gardencivics.admin")) return;
        if (!organizations.has(
                context.organization().id(),
                actor.getUniqueId(),
                OrganizationCapability.PAYROLL_RUN)) {
            throw new IllegalArgumentException("Your government role cannot run payroll.");
        }
    }

    private OrganizationView fresh(UUID organizationId) {
        return organizations.find(organizationId)
                .orElseThrow(() -> new IllegalStateException("Government organization is unavailable."));
    }

    private String periodKey() {
        return LocalDate.now(ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toString();
    }

    private boolean isDuplicate(Exception exception) {
        String message = exception.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("unique") || lower.contains("duplicate");
    }

    private void publishCompleted(
            GovernmentContext context,
            UUID runId,
            String period,
            long total,
            int recipients,
            long balanceAfter
    ) {
        publish(
                IntegrationEventType.PAYROLL_COMPLETED,
                "government",
                context.organization().id().toString(),
                "{\"runUuid\":\"" + runId
                        + "\",\"period\":\"" + period
                        + "\",\"total\":" + total
                        + ",\"recipients\":" + recipients
                        + ",\"balanceAfter\":" + balanceAfter + "}"
        );
    }

    private void publishFailure(
            GovernmentContext context,
            UUID runId,
            String period,
            long total,
            long unrecovered,
            String reason
    ) {
        publish(
                IntegrationEventType.PAYROLL_FAILED,
                "government",
                context.organization().id().toString(),
                "{\"runUuid\":\"" + runId
                        + "\",\"period\":\"" + period
                        + "\",\"total\":" + total
                        + ",\"unrecovered\":" + unrecovered
                        + ",\"reason\":\"" + reason + "\"}"
        );
    }

    private void publish(
            IntegrationEventType type,
            String aggregateType,
            String aggregateId,
            String payload
    ) {
        try {
            platform.integrations().publish(type, aggregateType, aggregateId, payload);
        } catch (SQLException exception) {
            plugin.getLogger().warning(
                    "Could not queue " + type + " event for Iris: " + exception.getMessage());
        }
    }

    public record PayrollRecipient(
            UUID playerId,
            String roleKey,
            String roleName,
            long amount
    ) {
    }

    public record PayrollPreview(
            String periodKey,
            UUID organizationId,
            String governmentName,
            List<PayrollRecipient> recipients,
            long total,
            long treasury,
            String existingStatus
    ) {
        public PayrollPreview {
            recipients = List.copyOf(recipients);
        }
    }

    public record PayrollResult(
            UUID runId,
            String periodKey,
            int recipientCount,
            long total,
            long treasuryAfter,
            List<PayrollRecipient> recipients
    ) {
        public PayrollResult {
            recipients = List.copyOf(recipients);
        }
    }

    private record PayrollRunState(UUID id, String status) {
    }
}
