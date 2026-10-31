package tz.go.tirdo.cbrms.events;

import java.util.UUID;

/** The bill an event is about. The caller must hold the bill row lock when appending (per-bill sequence). */
public record EventSubject(UUID billId, UUID sourceSystemId, String sourceSystemCode, String sourceReference) {}
