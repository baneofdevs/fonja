package com.fonja.common.audit;

import java.util.Map;

// TEMPORARY scratch stub — not a real implementation. Added only to unblock a
// local compile check of the campaign module; deleted immediately after.
public class AuditEvent {
    public static AuditEvent of(String eventType, String entityType, Long entityId, Long actorUserId, Map<String, ?> metadata) {
        return new AuditEvent();
    }
}
