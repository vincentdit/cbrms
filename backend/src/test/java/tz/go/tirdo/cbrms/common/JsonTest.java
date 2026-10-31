package tz.go.tirdo.cbrms.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void writesMoneyAsDecimalString() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amount", new BigDecimal("45000.00"));
        m.put("currency", "TZS");
        m.put("sequence", 3L);
        m.put("lines", List.of(1, 2));
        m.put("note", null);
        assertEquals("{\"amount\":\"45000.00\",\"currency\":\"TZS\",\"sequence\":3,\"lines\":[1,2],\"note\":null}", Json.write(m));
    }

    @Test
    void escapesStrings() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", Json.write("a\"b\\c\nd\u0001"));
    }
}
