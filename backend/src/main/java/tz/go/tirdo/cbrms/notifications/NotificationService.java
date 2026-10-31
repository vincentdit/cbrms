package tz.go.tirdo.cbrms.notifications;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Queues client notices inside the caller's transaction, so a notice exists only if the event committed. */
@Service
public class NotificationService {
    private static final DateTimeFormatter DUE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private record Contact(String channel, String value, String language) {}

    private final JdbcClient jdbc;

    public NotificationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Queues one notice per payer contact (SMS for phone, email for email). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int enqueueForBill(UUID billId, String template, Map<String, String> values) {
        List<Contact> contacts = jdbc.sql("""
                SELECT CASE c.channel WHEN 'PHONE' THEN 'SMS' ELSE 'EMAIL' END, c.value, p.language
                  FROM bill b JOIN payer p ON p.id = b.payer_id JOIN payer_contact c ON c.payer_id = p.id
                 WHERE b.id = :id
                """)
            .param("id", billId)
            .query((rs, n) -> new Contact(rs.getString(1), rs.getString(2), rs.getString(3)))
            .list();
        for (Contact c : contacts) {
            insert(billId, c.channel(), c.value(), template, c.language(), NoticeTemplates.render(template, c.language(), values));
        }
        return contacts.size();
    }

    /** Queues an SMS to the bill payer's phone only (used for one-time codes). Returns false if there is no phone. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueueSmsForBill(UUID billId, String template, Map<String, String> values) {
        List<Contact> phones = jdbc.sql("""
                SELECT 'SMS', c.value, p.language
                  FROM bill b JOIN payer p ON p.id = b.payer_id JOIN payer_contact c ON c.payer_id = p.id
                 WHERE b.id = :id AND c.channel = 'PHONE'
                 ORDER BY c.created_at DESC LIMIT 1
                """)
            .param("id", billId)
            .query((rs, n) -> new Contact(rs.getString(1), rs.getString(2), rs.getString(3)))
            .list();
        phones.forEach(c -> insert(billId, c.channel(), c.value(), template, c.language(), NoticeTemplates.render(template, c.language(), values)));
        return !phones.isEmpty();
    }

    private void insert(UUID billId, String channel, String recipient, String template, String language, String body) {
        jdbc.sql("""
                INSERT INTO client_notification (bill_id, channel, recipient, template, language, body)
                VALUES (:bill, :channel, :recipient, :template, :language, :body)
                """)
            .param("bill", billId)
            .param("channel", channel)
            .param("recipient", recipient)
            .param("template", template)
            .param("language", language)
            .param("body", body)
            .update();
    }

    public static String amount(BigDecimal value) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return f.format(value);
    }

    public static String date(LocalDate d) {
        return d.format(DUE);
    }

    public static String controlNumber(String cn) {
        return cn == null ? "" : cn.replaceAll("(\\d{4})(?=\\d)", "$1 ");
    }
}
