package tz.go.tirdo.cbrms.billing;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Payers are kept per source platform (restricted personal data, PDPA 2022). */
@Repository
public class PayerRepository {
    private final JdbcClient jdbc;

    public PayerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID upsert(UUID sourceSystemId, CreateBillRequest.Payer payer, String language) {
        UUID payerId = jdbc.sql("""
                INSERT INTO payer (source_system_id, payer_reference, display_name, language)
                VALUES (:src, :ref, :name, :language)
                ON CONFLICT (source_system_id, payer_reference)
                  DO UPDATE SET display_name = EXCLUDED.display_name, language = EXCLUDED.language
                RETURNING id
                """)
            .param("language", language)
            .param("src", sourceSystemId)
            .param("ref", payer.reference())
            .param("name", payer.name())
            .query(UUID.class)
            .single();
        addContact(payerId, "PHONE", payer.phone());
        addContact(payerId, "EMAIL", payer.email());
        return payerId;
    }

    private void addContact(UUID payerId, String channel, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        jdbc.sql("INSERT INTO payer_contact (payer_id, channel, value) VALUES (:payer, :channel, :value) ON CONFLICT DO NOTHING")
            .param("payer", payerId)
            .param("channel", channel)
            .param("value", value.trim())
            .update();
    }
}
