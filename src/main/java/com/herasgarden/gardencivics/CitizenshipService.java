package com.herasgarden.gardencivics;

import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.integration.IntegrationEventType;
import com.herasgarden.gardencore.api.land.GardenTerritoryDirectory;
import com.herasgarden.gardencore.api.land.TerritorySummary;
import com.herasgarden.gardencore.api.membership.TerritoryMembershipProvider;
import com.herasgarden.gardencore.api.storage.GardenStorage;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Canonical citizenship store. The table name is intentionally retained from
 * the previous GardenLands implementation so existing citizenship rows migrate
 * by ownership, not by copying data.
 */
public final class CitizenshipService implements TerritoryMembershipProvider {
    private final JavaPlugin plugin;
    private final GardenPlatform platform;
    private final GardenStorage storage;
    private final GardenTerritoryDirectory territories;
    private final Map<UUID, UUID> affiliations = new ConcurrentHashMap<>();

    public CitizenshipService(JavaPlugin plugin, GardenPlatform platform, GardenTerritoryDirectory territories) {
        this.plugin = plugin;
        this.platform = platform;
        this.storage = platform.storage();
        this.territories = territories;
    }

    public synchronized void refresh() throws SQLException {
        Map<UUID, UUID> loaded = new ConcurrentHashMap<>();
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT player_uuid, territory_claim_uuid FROM gl_citizenships");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                UUID identityId = UUID.fromString(result.getString("player_uuid"));
                UUID territoryId = UUID.fromString(result.getString("territory_claim_uuid"));
                if (territories.findByClaim(territoryId).isPresent()) {
                    loaded.put(identityId, territoryId);
                }
            }
        }
        affiliations.clear();
        affiliations.putAll(loaded);
    }

    @Override
    public Optional<UUID> territoryClaimOf(UUID identityId) {
        return Optional.ofNullable(affiliations.get(identityId));
    }

    public Optional<TerritorySummary> territoryOf(UUID identityId) {
        UUID claimId = affiliations.get(identityId);
        return claimId == null ? Optional.empty() : territories.findByClaim(claimId);
    }

    public TerritorySummary join(UUID identityId, String territoryName) throws SQLException {
        TerritorySummary territory = territories.findByName(territoryName)
                .orElseThrow(() -> new IllegalArgumentException("That territory does not exist."));
        setMembership(identityId, territory.claimId());
        return territory;
    }

    @Override
    public void setMembership(UUID identityId, UUID territoryClaimId) throws SQLException {
        TerritorySummary territory = territories.findByClaim(territoryClaimId)
                .orElseThrow(() -> new IllegalArgumentException("That territory does not exist."));
        long now = System.currentTimeMillis();
        try (Connection connection = storage.connection()) {
            int changed;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE gl_citizenships SET territory_claim_uuid = ?, joined_at = ? WHERE player_uuid = ?")) {
                update.setString(1, territory.claimId().toString());
                update.setLong(2, now);
                update.setString(3, identityId.toString());
                changed = update.executeUpdate();
            }
            if (changed == 0) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO gl_citizenships (player_uuid, territory_claim_uuid, joined_at) VALUES (?, ?, ?)")) {
                    insert.setString(1, identityId.toString());
                    insert.setString(2, territory.claimId().toString());
                    insert.setLong(3, now);
                    insert.executeUpdate();
                }
            }
        }
        affiliations.put(identityId, territory.claimId());
        publish(identityId, territory);
    }

    @Override
    public boolean clearMembership(UUID identityId) throws SQLException {
        int changed;
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM gl_citizenships WHERE player_uuid = ?")) {
            statement.setString(1, identityId.toString());
            changed = statement.executeUpdate();
        }
        if (changed > 0) {
            affiliations.remove(identityId);
            try {
                platform.integrations().publish(
                        IntegrationEventType.CITIZEN_AFFILIATION_CHANGED,
                        "player",
                        identityId.toString(),
                        "{\"territory\":null}"
                );
            } catch (SQLException exception) {
                plugin.getLogger().warning("Citizenship changed, but the integration event could not be queued: "
                        + exception.getMessage());
            }
        }
        return changed > 0;
    }

    @Override
    public List<UUID> members(UUID territoryClaimId) {
        List<UUID> result = new ArrayList<>();
        affiliations.forEach((identity, territory) -> {
            if (territory.equals(territoryClaimId)) {
                result.add(identity);
            }
        });
        return List.copyOf(result);
    }

    public int clearTerritory(UUID territoryClaimId) throws SQLException {
        int changed;
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM gl_citizenships WHERE territory_claim_uuid = ?")) {
            statement.setString(1, territoryClaimId.toString());
            changed = statement.executeUpdate();
        }
        affiliations.entrySet().removeIf(entry -> entry.getValue().equals(territoryClaimId));
        return changed;
    }

    private void publish(UUID identityId, TerritorySummary territory) {
        try {
            platform.integrations().publish(
                    IntegrationEventType.CITIZEN_AFFILIATION_CHANGED,
                    "player",
                    identityId.toString(),
                    "{\"territory\":\"" + escape(territory.name())
                            + "\",\"territoryClaimUuid\":\"" + territory.claimId() + "\"}"
            );
        } catch (SQLException exception) {
            plugin.getLogger().warning("Citizenship changed, but the integration event could not be queued: "
                    + exception.getMessage());
        }
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
