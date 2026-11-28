package net.onelitefeather.blackhole.backend.audit;

import jakarta.inject.Singleton;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Refuses an audit payload that carries an IP address or a raw UUID. The only UUIDs allowed are the ids the
 * entry itself owns: the audit id and the staff and recording oids.
 */
@Singleton
public class AuditPayloadGuard {

    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b");
    private static final Pattern IPV6 = Pattern.compile("\\b[0-9A-Fa-f]{1,4}(:[0-9A-Fa-f]{0,4}){3,7}\\b");
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * @throws IllegalArgumentException when a value of the payload is an IP address or a UUID that is not one of
     *                                  the entry's own ids
     */
    public void check(Map<String, Object> payload, UUID actorOid, UUID auditId) {
        for (Map.Entry<String, Object> field : payload.entrySet()) {
            if (!(field.getValue() instanceof String text)) {
                continue;
            }
            if (IPV4.matcher(text).find() || IPV6.matcher(text).find()) {
                throw new IllegalArgumentException("audit field " + field.getKey() + " carries an IP address");
            }
            var uuids = UUID_TEXT.matcher(text);
            while (uuids.find()) {
                String found = uuids.group();
                if (!found.equalsIgnoreCase(actorOid.toString()) && !found.equalsIgnoreCase(auditId.toString())) {
                    throw new IllegalArgumentException("audit field " + field.getKey() + " carries a raw UUID");
                }
            }
        }
    }
}
