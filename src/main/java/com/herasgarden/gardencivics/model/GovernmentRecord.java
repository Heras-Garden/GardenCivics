package com.herasgarden.gardencivics.model;

import java.util.UUID;

public record GovernmentRecord(
        UUID territoryClaimId,
        UUID organizationId,
        UUID createdBy,
        long createdAt
) {
}
