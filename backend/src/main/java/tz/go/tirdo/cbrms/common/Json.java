package tz.go.tirdo.cbrms.common;

import java.math.BigDecimal;
import java.time.temporal.TemporalAccessor;
import java.util.Iterator;
import java.util.Map;

/**
 * Minimal deterministic JSON writer for outbox payloads and digests.
 * Money (BigDecimal) is written as a decimal string, never a binary number (SRS section 6).
 * Map iteration order is preserved, so use LinkedHashMap for stable output.
 */
public final class Json {
    private Json() {}

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            string(sb, s);
        } else if (v instanceof BigDecimal d) {
            string(sb, d.toPlainString());
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Enum<?> e) {
            string(sb, e.name());
        } else if (v instanceof TemporalAccessor || v instanceof java.util.UUID) {
            string(sb, v.toString());
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                string(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
                if (it.hasNext()) {
                    sb.append(',');
                }
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> list) {
            sb.append('[');
            Iterator<?> it = list.iterator();
            while (it.hasNext()) {
                write(sb, it.next());
                if (it.hasNext()) {
                    sb.append(',');
                }
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value type: " + v.getClass().getName());
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
