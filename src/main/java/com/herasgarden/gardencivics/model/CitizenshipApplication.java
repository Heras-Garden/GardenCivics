package com.herasgarden.gardencivics.model;

import java.util.UUID;

public record CitizenshipApplication(
        UUID id,
        UUID playerId,
        UUID territoryClaimId,
        String message,
        String status,
        UUID reviewedBy,
        long createdAt,
        long updatedAt
) {
    public boolean pending() {
        return "PENDING".equals(status);
    }
}
