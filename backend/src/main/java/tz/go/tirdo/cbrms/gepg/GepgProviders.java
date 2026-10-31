package tz.go.tirdo.cbrms.gepg;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tz.go.tirdo.cbrms.config.CbrmsProperties;

/** The GePG identity (SP code, environment) this deployment uses. */
@Component
public class GepgProviders {
    public record Provider(UUID id, String code, String spCode, String subSpCode, String systemId) {}

    private final JdbcClient jdbc;
    private final CbrmsProperties props;
    private volatile Provider cached;

    public GepgProviders(JdbcClient jdbc, CbrmsProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public Provider current() {
        Provider p = cached;
        if (p == null) {
            p = jdbc.sql("SELECT id, code, sp_code, sub_sp_code, system_id FROM gepg_provider WHERE code = :code AND status = 'ACTIVE'")
                    .param("code", props.gepg().providerCode())
                    .query((rs, n) -> new Provider(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                    .optional()
                    .orElseThrow(() -> new IllegalStateException("No active gepg_provider with code " + props.gepg().providerCode()));
            cached = p;
        }
        return p;
    }
}
