package tz.go.tirdo.cbrms.ops;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operations settings (cbrms.ops).
 * @param backupExpected  true where backups run (production): a missing or stale backup then raises an alert
 * @param archiveExpected true where WAL archiving is on (production): archive lag then raises an alert
 * @param certificates    certificates to watch: a PEM file path, or a recorded expiry date when the file is held elsewhere
 */
@ConfigurationProperties("cbrms.ops")
public record OpsProperties(Boolean backupExpected, Boolean archiveExpected, List<Certificate> certificates) {

    /** notAfter is an ISO date (yyyy-MM-dd), parsed by the monitor so a typo shows as an error on the Operations page. */
    public record Certificate(String name, String path, String notAfter) {}

    public boolean backups() {
        return Boolean.TRUE.equals(backupExpected);
    }

    public boolean archiving() {
        return Boolean.TRUE.equals(archiveExpected);
    }

    public List<Certificate> certs() {
        return certificates == null ? List.of() : certificates;
    }
}
