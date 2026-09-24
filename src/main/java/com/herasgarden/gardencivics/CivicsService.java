package com.herasgarden.gardencivics;

import com.herasgarden.gardencivics.model.CitizenshipApplication;
import com.herasgarden.gardencivics.model.GovernmentRecord;
import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.civics.TerritoryGovernmentRegistrar;
import com.herasgarden.gardencore.claim.GovernmentType;
import com.herasgarden.gardencore.api.integration.IntegrationEventType;
import com.herasgarden.gardencore.api.membership.TerritoryMembershipProvider;
import com.herasgarden.gardencore.api.land.GardenTerritoryDirectory;
import com.herasgarden.gardencore.api.land.TerritorySummary;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class CivicsService implements TerritoryGovernmentRegistrar {
    private final JavaPlugin plugin;
    private final GardenPlatform platform;
    private final OrganizationDirectory organizations;
    private final GardenTerritoryDirectory territories;
    private final TerritoryMembershipProvider citizenship;
    private final int applicationMessageMax;

    public CivicsService(
            JavaPlugin plugin,
            GardenPlatform platform,
            OrganizationDirectory organizations,
            GardenTerritoryDirectory territories,
            TerritoryMembershipProvider citizenship,
            int applicationMessageMax
    ) {
        this.plugin = plugin;
        this.platform = platform;
        this.organizations = organizations;
        this.territories = territories;
        this.citizenship = citizenship;
        this.applicationMessageMax = Math.max(40, applicationMessageMax);
    }

    public GovernmentContext createGovernment(Player founder, String territoryName, GovernmentType governmentType)
            throws SQLException {
        TerritorySummary territory = territories.findByName(territoryName)
                .orElseThrow(() -> new IllegalArgumentException("That territory does not exist."));
        if (!territories.canManage(founder, territory.claimId())
                && !founder.hasPermission("gardencivics.admin")) {
            throw new IllegalArgumentException("You must manage that territory to create its government.");
        }
        UUID organizationId = createMapping(
                founder, territory.claimId(), territory.name(), governmentType == null ? GovernmentType.COUNCIL : governmentType);
        return governmentForTerritory(territory.claimId())
                .orElseThrow(() -> new IllegalStateException("Government mapping could not be reloaded."));
    }

    public GovernmentContext createGovernment(Player founder, String territoryName) throws SQLException {
        return createGovernment(founder, territoryName, GovernmentType.COUNCIL);
    }

    @Override
    public UUID createForTerritory(
            Player founder,
            UUID territoryClaimId,
            String territoryName,
            GovernmentType governmentType
    ) throws SQLException {
        if (governmentType == null) {
            throw new IllegalArgumentException("A territory must choose a government type.");
        }
        return createMapping(founder, territoryClaimId, territoryName, governmentType);
    }

    private UUID createMapping(
            Player founder,
            UUID territoryClaimId,
            String territoryName,
            GovernmentType governmentType
    ) throws SQLException {
        if (mappingForTerritory(territoryClaimId).isPresent()) {
            throw new IllegalArgumentException("That territory already has a government.");
        }

        String governmentName = territoryName + " Government";
        OrganizationView organization = organizations.findByName(governmentName).orElse(null);
        if (organization == null) {
            organization = organizations.createGovernment(founder.getUniqueId(), governmentName);
        } else if (!"GOVERNMENT".equals(organization.type())
                || !organization.founderId().equals(founder.getUniqueId())) {
            throw new IllegalArgumentException("The government name for this territory is already in use.");
        }

        defineDefaultRoles(organization.id(), governmentType);

        long now = System.currentTimeMillis();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO gcv_governments "
                             + "(territory_claim_uuid, organization_uuid, government_type, created_by, created_at) "
                             + "VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, territoryClaimId.toString());
            statement.setString(2, organization.id().toString());
            statement.setString(3, governmentType.name());
            statement.setString(4, founder.getUniqueId().toString());
            statement.setLong(5, now);
            statement.executeUpdate();
        }

        if (citizenship.territoryClaimOf(founder.getUniqueId()).isEmpty()) {
            try {
                citizenship.setMembership(founder.getUniqueId(), territoryClaimId);
            } catch (SQLException exception) {
                plugin.getLogger().warning("Government created, but founder citizenship could not be set: "
                        + exception.getMessage());
            }
        }

        publish(
                IntegrationEventType.GOVERNMENT_CREATED,
                "government",
                organization.id().toString(),
                "{\"territoryClaimUuid\":\"" + territoryClaimId
                        + "\",\"territory\":\"" + json(territoryName)
                        + "\",\"governmentType\":\"" + governmentType.name()
                        + "\",\"organizationUuid\":\"" + organization.id()
                        + "\",\"founderUuid\":\"" + founder.getUniqueId() + "\"}"
        );
        return organization.id();
    }

    public Optional<GovernmentContext> governmentForTerritory(UUID territoryClaimId) throws SQLException {
        GovernmentRecord mapping = mappingForTerritory(territoryClaimId).orElse(null);
        return mapping == null ? Optional.empty() : context(mapping);
    }

    public Optional<GovernmentContext> governmentForTerritoryName(String territoryName) throws SQLException {
        TerritorySummary territory = territories.findByName(territoryName).orElse(null);
        return territory == null ? Optional.empty() : governmentForTerritory(territory.claimId());
    }

    public Optional<GovernmentContext> governmentForActor(UUID playerId) throws SQLException {
        List<GovernmentContext> matches = new ArrayList<>();
        for (GovernmentRecord mapping : mappings()) {
            GovernmentContext context = context(mapping).orElse(null);
            if (context != null && context.organization().memberRoles().containsKey(playerId)) {
                matches.add(context);
            }
        }
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() == 1) {
            return Optional.of(matches.getFirst());
        }

        UUID citizenshipTerritory = citizenship.territoryClaimOf(playerId).orElse(null);
        if (citizenshipTerritory != null) {
            for (GovernmentContext match : matches) {
                if (match.territory().claimId().equals(citizenshipTerritory)) {
                    return Optional.of(match);
                }
            }
        }
        throw new IllegalArgumentException(
                "You hold positions in multiple governments. Set your territory citizenship to choose the active one.");
    }

    public Optional<GovernmentContext> governmentForPlayer(UUID playerId) throws SQLException {
        UUID territoryClaimId = citizenship.territoryClaimOf(playerId).orElse(null);
        if (territoryClaimId != null) {
            Optional<GovernmentContext> civicHome = governmentForTerritory(territoryClaimId);
            if (civicHome.isPresent()) {
                return civicHome;
            }
        }
        return governmentForActor(playerId);
    }

    public List<TerritorySummary> territories() {
        return territories.list();
    }

    public GovernmentContext requireActorGovernment(Player actor) throws SQLException {
        return governmentForActor(actor.getUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("You do not hold a position in a Garden government."));
    }

    public OrganizationView createRole(Player actor, String roleKey, String displayName) throws SQLException {
        GovernmentContext context = requireActorGovernment(actor);
        requireCapability(actor, context, OrganizationCapability.ROLE_MANAGE);

        String key = normalizeRoleKey(roleKey);
        if ("owner".equals(key)) {
            throw new IllegalArgumentException("The founder role cannot be replaced.");
        }
        OrganizationView current = freshOrganization(context.organization().id());
        if (current.roles().containsKey(key)) {
            throw new IllegalArgumentException("That government role already exists.");
        }

        OrganizationView updated = organizations.defineRole(
                current.id(), key, cleanRoleDisplayName(displayName), 0L, Set.of());
        publishRoleDefinition(context, key, actor.getUniqueId(), "CREATED", 0L, Set.of());
        return updated;
    }

    public OrganizationView setRoleSalary(Player actor, String roleKey, long salary) throws SQLException {
        GovernmentContext context = requireActorGovernment(actor);
        requireCapability(actor, context, OrganizationCapability.ROLE_MANAGE);
        if (salary < 0) {
            throw new IllegalArgumentException("Role salary cannot be negative.");
        }

        OrganizationRoleView role = requireEditableRole(context.organization().id(), roleKey);
        OrganizationView updated = organizations.defineRole(
                context.organization().id(),
                role.key(),
                role.displayName(),
                salary,
                role.capabilities()
        );
        publishRoleDefinition(
                context, role.key(), actor.getUniqueId(), "SALARY_CHANGED", salary, role.capabilities());
        return updated;
    }

    public OrganizationView setRoleCapability(
            Player actor,
            String roleKey,
            OrganizationCapability capability,
            boolean enabled
    ) throws SQLException {
        GovernmentContext context = requireActorGovernment(actor);
        requireCapability(actor, context, OrganizationCapability.ROLE_MANAGE);
        if (capability == null) {
            throw new IllegalArgumentException("Organization capability is required.");
        }

        OrganizationRoleView role = requireEditableRole(context.organization().id(), roleKey);
        EnumSet<OrganizationCapability> capabilities = role.capabilities().isEmpty()
                ? EnumSet.noneOf(OrganizationCapability.class)
                : EnumSet.copyOf(role.capabilities());
        if (enabled) {
            capabilities.add(capability);
        } else {
            capabilities.remove(capability);
        }

        OrganizationView updated = organizations.defineRole(
                context.organization().id(),
                role.key(),
                role.displayName(),
                role.salary(),
                capabilities
        );
        publishRoleDefinition(
                context,
                role.key(),
                actor.getUniqueId(),
                enabled ? "CAPABILITY_GRANTED" : "CAPABILITY_REVOKED",
                role.salary(),
                capabilities
        );
        return updated;
    }

    public OrganizationRoleView role(Player actor, String roleKey) throws SQLException {
        GovernmentContext context = requireActorGovernment(actor);
        String key = normalizeRoleKey(roleKey);
        OrganizationRoleView role = freshOrganization(context.organization().id()).roles().get(key);
        if (role == null) {
            throw new IllegalArgumentException("That government role does not exist.");
        }
        return role;
    }

    public OrganizationView appoint(Player actor, UUID targetPlayerId, String roleKey) throws SQLException {
        GovernmentContext context = requireActorGovernment(actor);
        requireCapability(actor, context, OrganizationCapability.ROLE_MANAGE);
        if ("owner".equalsIgnoreCase(roleKey)) {
            throw new IllegalArgumentException("The founder position cannot be assigned.");
        }
        OrganizationView updated = organizations.assignMember(context.organization().id(), targetPlayerId, roleKey);
        publishRoleChange(context, targetPlayerId, roleKey, actor.getUniqueId(), "ASSIGNED");
        return updated;
    }

    public boolean removeOfficial(Player actor, UUID targetPlayerId) throws SQLException {
        GovernmentContext context = requireActorGovernment(actor);
        if (!organizations.has(context.organization().id(), actor.getUniqueId(), OrganizationCapability.MEMBER_REMOVE)
                && !organizations.has(context.organization().id(), actor.getUniqueId(), OrganizationCapability.ROLE_MANAGE)
                && !actor.hasPermission("gardencivics.admin")) {
            throw new IllegalArgumentException("Your government role cannot remove officials.");
        }
        boolean changed = organizations.removeMember(context.organization().id(), targetPlayerId);
        if (changed) {
            publishRoleChange(context, targetPlayerId, null, actor.getUniqueId(), "REMOVED");
        }
        return changed;
    }

    public long treasury(Player actor) throws SQLException {
        GovernmentContext context = requireActorGovernment(actor);
        requireCapability(actor, context, OrganizationCapability.TREASURY_VIEW);
        return freshOrganization(context.organization().id()).treasury();
    }

    public long deposit(Player actor, long amount) throws SQLException {
        requirePositive(amount);
        GovernmentContext context = requireActorGovernment(actor);
        requireCapability(actor, context, OrganizationCapability.TREASURY_DEPOSIT);

        if (!platform.currency().withdraw(actor.getUniqueId(), amount)) {
            throw new IllegalArgumentException("You do not have enough Obols.");
        }

        try {
            if (!organizations.creditTreasury(context.organization().id(), amount)) {
                platform.currency().deposit(actor.getUniqueId(), amount);
                throw new IllegalArgumentException("The government treasury could not be credited.");
            }
        } catch (SQLException exception) {
            platform.currency().deposit(actor.getUniqueId(), amount);
            throw exception;
        }

        long balance = freshOrganization(context.organization().id()).treasury();
        journalTreasury(context.organization().id(), actor.getUniqueId(), "DEPOSIT", amount, balance);
        publishTreasury(context, actor.getUniqueId(), "DEPOSIT", amount, balance);
        return balance;
    }

    public long withdraw(Player actor, long amount) throws SQLException {
        requirePositive(amount);
        GovernmentContext context = requireActorGovernment(actor);
        requireCapability(actor, context, OrganizationCapability.TREASURY_WITHDRAW);

        if (!organizations.debitTreasury(context.organization().id(), amount)) {
            throw new IllegalArgumentException("The government treasury does not have enough Obols.");
        }

        if (!platform.currency().deposit(actor.getUniqueId(), amount)) {
            boolean restored = false;
            String failure = "Treasury restoration returned false";
            try {
                restored = organizations.creditTreasury(context.organization().id(), amount);
            } catch (SQLException rollbackFailure) {
                failure = rollbackFailure.getMessage();
            }
            if (!restored) {
                recordTreasuryRecovery(context.organization().id(), actor.getUniqueId(), amount, failure);
                plugin.getLogger().severe("CRITICAL: government treasury withdrawal of ⟡ " + amount
                        + " for " + actor.getUniqueId() + " requires admin recovery.");
                throw new IllegalArgumentException(
                        "Your balance could not receive the withdrawal and treasury restoration needs administrator review.");
            }
            throw new IllegalArgumentException("Your balance could not receive the withdrawal. The treasury was restored.");
        }

        long balance = freshOrganization(context.organization().id()).treasury();
        journalTreasury(context.organization().id(), actor.getUniqueId(), "WITHDRAW", amount, balance);
        publishTreasury(context, actor.getUniqueId(), "WITHDRAW", amount, balance);
        return balance;
    }

    private void recordTreasuryRecovery(UUID organizationId, UUID playerId, long amount, String reason) {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO gcv_treasury_recovery "
                             + "(recovery_uuid, organization_uuid, player_uuid, amount, reason, status, created_at) "
                             + "VALUES (?, ?, ?, ?, ?, 'OPEN', ?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, organizationId.toString());
            statement.setString(3, playerId.toString());
            statement.setLong(4, amount);
            statement.setString(5, reason == null ? "Unknown compensation failure" : reason.substring(0, Math.min(192, reason.length())));
            statement.setLong(6, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException recoveryFailure) {
            plugin.getLogger().severe("CRITICAL: could not record treasury recovery row: " + recoveryFailure.getMessage());
        }
    }

    public CitizenshipApplication apply(Player player, String territoryName, String message) throws SQLException {
        TerritorySummary territory = territories.findByName(territoryName)
                .orElseThrow(() -> new IllegalArgumentException("That territory does not exist."));
        if (governmentForTerritory(territory.claimId()).isEmpty()) {
            throw new IllegalArgumentException("That territory does not have a government to review applications.");
        }
        if (citizenship.territoryClaimOf(player.getUniqueId())
                .filter(territory.claimId()::equals)
                .isPresent()) {
            throw new IllegalArgumentException("You are already a citizen of that territory.");
        }
        if (hasPendingApplication(player.getUniqueId(), territory.claimId())) {
            throw new IllegalArgumentException("You already have a pending application for that territory.");
        }

        String cleanMessage = cleanApplicationMessage(message);
        long now = System.currentTimeMillis();
        CitizenshipApplication application = new CitizenshipApplication(
                UUID.randomUUID(),
                player.getUniqueId(),
                territory.claimId(),
                cleanMessage,
                "PENDING",
                null,
                now,
                now
        );

        try (Connection connection = platform.storage().connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT application_uuid FROM gcv_citizenship_applications "
                                + "WHERE player_uuid = ? AND territory_claim_uuid = ? AND status = 'PENDING' FOR UPDATE")) {
                    lock.setString(1, application.playerId().toString());
                    lock.setString(2, application.territoryClaimId().toString());
                    try (ResultSet existing = lock.executeQuery()) {
                        if (existing.next()) {
                            throw new IllegalArgumentException("You already have a pending application for that territory.");
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO gcv_citizenship_applications "
                                + "(application_uuid, player_uuid, territory_claim_uuid, message, status, reviewed_by, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, 'PENDING', NULL, ?, ?)")) {
                    statement.setString(1, application.id().toString());
                    statement.setString(2, application.playerId().toString());
                    statement.setString(3, application.territoryClaimId().toString());
                    statement.setString(4, application.message());
                    statement.setLong(5, now);
                    statement.setLong(6, now);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }

        publish(
                IntegrationEventType.CITIZENSHIP_APPLICATION_SUBMITTED,
                "citizenship-application",
                application.id().toString(),
                "{\"playerUuid\":\"" + player.getUniqueId()
                        + "\",\"territoryClaimUuid\":\"" + territory.claimId()
                        + "\",\"territory\":\"" + json(territory.name()) + "\"}"
        );
        return application;
    }

    public List<CitizenshipApplication> pendingApplications(Player reviewer) throws SQLException {
        GovernmentContext context = requireActorGovernment(reviewer);
        requireReviewCapability(reviewer, context);

        List<CitizenshipApplication> result = new ArrayList<>();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gcv_citizenship_applications "
                             + "WHERE territory_claim_uuid = ? AND status = 'PENDING' ORDER BY created_at ASC")) {
            statement.setString(1, context.territory().claimId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(readApplication(rows));
                }
            }
        }
        return List.copyOf(result);
    }

    public CitizenshipApplication review(Player reviewer, String applicationId, boolean approve) throws SQLException {
        CitizenshipApplication application = findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("That citizenship application does not exist."));
        if (!application.pending()) {
            throw new IllegalArgumentException("That citizenship application has already been reviewed.");
        }

        GovernmentContext context = governmentForTerritory(application.territoryClaimId())
                .orElseThrow(() -> new IllegalArgumentException("That territory no longer has a government."));
        requireReviewCapability(reviewer, context);

        long now = System.currentTimeMillis();
        String status = approve ? "APPROVED" : "REJECTED";
        int changed;
        if (approve) {
            try (Connection connection = platform.storage().connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE gcv_citizenship_applications SET status = 'APPROVING', reviewed_by = ?, updated_at = ? "
                                 + "WHERE application_uuid = ? AND status = 'PENDING'")) {
                statement.setString(1, reviewer.getUniqueId().toString());
                statement.setLong(2, now);
                statement.setString(3, application.id().toString());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalArgumentException("That citizenship application changed before it could be reviewed.");
                }
            }
            try {
                citizenship.setMembership(application.playerId(), application.territoryClaimId());
            } catch (SQLException | RuntimeException exception) {
                try (Connection connection = platform.storage().connection();
                     PreparedStatement statement = connection.prepareStatement(
                             "UPDATE gcv_citizenship_applications SET status = 'PENDING', reviewed_by = NULL, updated_at = ? "
                                     + "WHERE application_uuid = ? AND status = 'APPROVING'")) {
                    statement.setLong(1, System.currentTimeMillis());
                    statement.setString(2, application.id().toString());
                    statement.executeUpdate();
                }
                throw exception;
            }
        }
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gcv_citizenship_applications SET status = ?, reviewed_by = ?, updated_at = ? "
                             + "WHERE application_uuid = ? AND status = ?")) {
            statement.setString(1, status);
            statement.setString(2, reviewer.getUniqueId().toString());
            statement.setLong(3, now);
            statement.setString(4, application.id().toString());
            statement.setString(5, approve ? "APPROVING" : "PENDING");
            changed = statement.executeUpdate();
        }
        if (changed != 1) {
            throw new IllegalArgumentException("That citizenship application changed before it could be reviewed.");
        }

        CitizenshipApplication reviewed = new CitizenshipApplication(
                application.id(),
                application.playerId(),
                application.territoryClaimId(),
                application.message(),
                status,
                reviewer.getUniqueId(),
                application.createdAt(),
                now
        );
        publish(
                IntegrationEventType.CITIZENSHIP_APPLICATION_REVIEWED,
                "citizenship-application",
                reviewed.id().toString(),
                "{\"playerUuid\":\"" + reviewed.playerId()
                        + "\",\"territoryClaimUuid\":\"" + reviewed.territoryClaimId()
                        + "\",\"reviewerUuid\":\"" + reviewer.getUniqueId()
                        + "\",\"status\":\"" + status + "\"}"
        );
        return reviewed;
    }

    public Optional<CitizenshipApplication> findApplication(String value) throws SQLException {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            UUID id = UUID.fromString(value);
            return findApplication(id);
        } catch (IllegalArgumentException ignored) {
        }

        String prefix = value.trim().toLowerCase();
        if (prefix.length() < 8 || !prefix.matches("[0-9a-f-]+")) {
            throw new IllegalArgumentException("Use at least the first 8 characters of the application ID.");
        }

        CitizenshipApplication match = null;
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gcv_citizenship_applications WHERE LOWER(application_uuid) LIKE ? LIMIT 2")) {
            statement.setString(1, prefix + "%");
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (match != null) {
                        throw new IllegalArgumentException("That application ID prefix matches more than one application.");
                    }
                    match = readApplication(rows);
                }
            }
        }
        return Optional.ofNullable(match);
    }

    public Optional<CitizenshipApplication> findApplication(UUID id) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gcv_citizenship_applications WHERE application_uuid = ? LIMIT 1")) {
            statement.setString(1, id.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readApplication(rows)) : Optional.empty();
            }
        }
    }

    private void defineDefaultRoles(UUID organizationId, GovernmentType governmentType) throws SQLException {
        switch (governmentType) {
            case COUNCIL -> organizations.defineRole(
                    organizationId, "council", "Council Member", 0L,
                    EnumSet.of(
                            OrganizationCapability.TREASURY_VIEW,
                            OrganizationCapability.CONTRACT_CREATE,
                            OrganizationCapability.CONTRACT_ACCEPT,
                            OrganizationCapability.MEMBER_INVITE
                    ));
            case MAYOR -> organizations.defineRole(
                    organizationId, "mayor", "Mayor", 0L, EnumSet.allOf(OrganizationCapability.class));
            case MONARCHY -> organizations.defineRole(
                    organizationId, "crown", "Crown", 0L, EnumSet.allOf(OrganizationCapability.class));
            case DIRECT_DEMOCRACY -> organizations.defineRole(
                    organizationId, "steward", "Steward", 0L,
                    EnumSet.of(
                            OrganizationCapability.TREASURY_VIEW,
                            OrganizationCapability.TREASURY_DEPOSIT,
                            OrganizationCapability.CONTRACT_CREATE,
                            OrganizationCapability.MEMBER_INVITE
                    ));
            case CUSTOM -> organizations.defineRole(
                    organizationId, "official", "Official", 0L,
                    EnumSet.of(OrganizationCapability.TREASURY_VIEW));
        }
        organizations.defineRole(
                organizationId,
                "treasurer",
                "Treasurer",
                0L,
                EnumSet.of(
                        OrganizationCapability.TREASURY_VIEW,
                        OrganizationCapability.TREASURY_DEPOSIT,
                        OrganizationCapability.TREASURY_WITHDRAW,
                        OrganizationCapability.PAYROLL_RUN
                )
        );
    }

    private Optional<GovernmentRecord> mappingForTerritory(UUID territoryClaimId) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gcv_governments WHERE territory_claim_uuid = ? LIMIT 1")) {
            statement.setString(1, territoryClaimId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readGovernment(rows)) : Optional.empty();
            }
        }
    }

    private List<GovernmentRecord> mappings() throws SQLException {
        List<GovernmentRecord> result = new ArrayList<>();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM gcv_governments");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(readGovernment(rows));
            }
        }
        return List.copyOf(result);
    }

    private Optional<GovernmentContext> context(GovernmentRecord mapping) {
        TerritorySummary territory = territories.findByClaim(mapping.territoryClaimId()).orElse(null);
        OrganizationView organization = organizations.find(mapping.organizationId()).orElse(null);
        return territory == null || organization == null
                ? Optional.empty()
                : Optional.of(new GovernmentContext(mapping, territory, organization));
    }

    private OrganizationView freshOrganization(UUID organizationId) {
        return organizations.find(organizationId)
                .orElseThrow(() -> new IllegalStateException("Government organization is unavailable."));
    }

    private OrganizationRoleView requireEditableRole(UUID organizationId, String roleKey) {
        String key = normalizeRoleKey(roleKey);
        if ("owner".equals(key)) {
            throw new IllegalArgumentException("The founder role cannot be edited.");
        }
        OrganizationRoleView role = freshOrganization(organizationId).roles().get(key);
        if (role == null) {
            throw new IllegalArgumentException("That government role does not exist.");
        }
        return role;
    }

    private String normalizeRoleKey(String value) {
        String key = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-")
                .replaceAll("-+", "-");
        if (key.startsWith("-")) key = key.substring(1);
        if (key.endsWith("-") && !key.isEmpty()) key = key.substring(0, key.length() - 1);
        if (key.isBlank() || key.length() > 32) {
            throw new IllegalArgumentException(
                    "Role keys must be 1 to 32 letters, numbers, dashes, or underscores.");
        }
        return key;
    }

    private String cleanRoleDisplayName(String value) {
        String clean = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (clean.isBlank() || clean.length() > 48) {
            throw new IllegalArgumentException("Role display names must be 1 to 48 characters.");
        }
        return clean;
    }

    private void requireCapability(Player actor, GovernmentContext context, OrganizationCapability capability) {
        if (actor.hasPermission("gardencivics.admin")) {
            return;
        }
        if (!organizations.has(context.organization().id(), actor.getUniqueId(), capability)) {
            throw new IllegalArgumentException("Your government role does not have permission to do that.");
        }
    }

    private void requireReviewCapability(Player actor, GovernmentContext context) {
        if (actor.hasPermission("gardencivics.admin")) {
            return;
        }
        boolean allowed = organizations.has(
                context.organization().id(), actor.getUniqueId(), OrganizationCapability.MEMBER_INVITE)
                || organizations.has(
                context.organization().id(), actor.getUniqueId(), OrganizationCapability.ROLE_MANAGE);
        if (!allowed) {
            throw new IllegalArgumentException("Your government role cannot review citizenship applications.");
        }
    }

    private boolean hasPendingApplication(UUID playerId, UUID territoryClaimId) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM gcv_citizenship_applications "
                             + "WHERE player_uuid = ? AND territory_claim_uuid = ? AND status = 'PENDING' LIMIT 1")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, territoryClaimId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private void journalTreasury(UUID organizationId, UUID actorId, String direction, long amount, long balanceAfter) {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO gcv_treasury_journal "
                             + "(transaction_uuid, organization_uuid, actor_uuid, direction, amount, balance_after, created_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, organizationId.toString());
            statement.setString(3, actorId.toString());
            statement.setString(4, direction);
            statement.setLong(5, amount);
            statement.setLong(6, balanceAfter);
            statement.setLong(7, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Treasury changed, but its civic journal row could not be written: "
                    + exception.getMessage());
        }
    }

    private void publishTreasury(
            GovernmentContext context,
            UUID actorId,
            String direction,
            long amount,
            long balance
    ) {
        publish(
                IntegrationEventType.TREASURY_CHANGED,
                "government",
                context.organization().id().toString(),
                "{\"territoryClaimUuid\":\"" + context.territory().claimId()
                        + "\",\"actorUuid\":\"" + actorId
                        + "\",\"direction\":\"" + direction
                        + "\",\"amount\":" + amount
                        + ",\"balance\":" + balance + "}"
        );
    }

    private void publishRoleDefinition(
            GovernmentContext context,
            String roleKey,
            UUID actorId,
            String action,
            long salary,
            Set<OrganizationCapability> capabilities
    ) {
        String capabilityJson = capabilities.stream()
                .map(capability -> "\"" + capability.name() + "\"")
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        publish(
                IntegrationEventType.GOVERNMENT_ROLE_CHANGED,
                "government",
                context.organization().id().toString(),
                "{\"territoryClaimUuid\":\"" + context.territory().claimId()
                        + "\",\"actorUuid\":\"" + actorId
                        + "\",\"action\":\"" + action
                        + "\",\"role\":\"" + json(roleKey)
                        + "\",\"salary\":" + salary
                        + ",\"capabilities\":[" + capabilityJson + "]}"
        );
    }

    private void publishRoleChange(
            GovernmentContext context,
            UUID targetId,
            String roleKey,
            UUID actorId,
            String action
    ) {
        publish(
                IntegrationEventType.GOVERNMENT_ROLE_CHANGED,
                "government",
                context.organization().id().toString(),
                "{\"territoryClaimUuid\":\"" + context.territory().claimId()
                        + "\",\"targetUuid\":\"" + targetId
                        + "\",\"actorUuid\":\"" + actorId
                        + "\",\"action\":\"" + action
                        + "\",\"role\":" + (roleKey == null ? "null" : "\"" + json(roleKey) + "\"") + "}"
        );
    }

    private void publish(IntegrationEventType type, String aggregateType, String aggregateId, String payload) {
        try {
            platform.integrations().publish(type, aggregateType, aggregateId, payload);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not queue " + type + " event for Iris: " + exception.getMessage());
        }
    }

    private GovernmentRecord readGovernment(ResultSet rows) throws SQLException {
        return new GovernmentRecord(
                UUID.fromString(rows.getString("territory_claim_uuid")),
                UUID.fromString(rows.getString("organization_uuid")),
                rows.getString("government_type"),
                UUID.fromString(rows.getString("created_by")),
                rows.getLong("created_at")
        );
    }

    private CitizenshipApplication readApplication(ResultSet rows) throws SQLException {
        String reviewer = rows.getString("reviewed_by");
        return new CitizenshipApplication(
                UUID.fromString(rows.getString("application_uuid")),
                UUID.fromString(rows.getString("player_uuid")),
                UUID.fromString(rows.getString("territory_claim_uuid")),
                rows.getString("message"),
                rows.getString("status"),
                reviewer == null ? null : UUID.fromString(reviewer),
                rows.getLong("created_at"),
                rows.getLong("updated_at")
        );
    }

    private String cleanApplicationMessage(String value) {
        String clean = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (clean.length() > applicationMessageMax) {
            clean = clean.substring(0, applicationMessageMax);
        }
        return clean;
    }

    private void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be a positive whole number of Obols.");
        }
    }

    private String json(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    public record GovernmentContext(
            GovernmentRecord mapping,
            TerritorySummary territory,
            OrganizationView organization
    ) {
    }
}
