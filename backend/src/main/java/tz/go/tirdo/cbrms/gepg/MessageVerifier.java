package tz.go.tirdo.cbrms.gepg;

/** Verifies an inbound GePG message exactly as the assigned contract requires (FR12). */
public interface MessageVerifier {
    boolean verify(String rawPayload, String signature);
}
