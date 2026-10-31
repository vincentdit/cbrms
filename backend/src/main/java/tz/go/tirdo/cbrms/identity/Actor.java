package tz.go.tirdo.cbrms.identity;

import java.util.UUID;

/** The authenticated caller, resolved to its app_user record. */
public record Actor(UUID userId, String subject, String kind, UUID sourceSystemId, String sourceSystemCode, boolean finance) {

    public boolean isPlatform() {
        return "MACHINE".equals(kind) && sourceSystemId != null;
    }
}
