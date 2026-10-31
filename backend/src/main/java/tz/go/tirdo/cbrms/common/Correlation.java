package tz.go.tirdo.cbrms.common;

import org.slf4j.MDC;

public final class Correlation {
    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private Correlation() {}

    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id == null ? "none" : id;
    }
}
