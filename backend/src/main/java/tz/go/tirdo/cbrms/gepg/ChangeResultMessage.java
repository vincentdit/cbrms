package tz.go.tirdo.cbrms.gepg;

/** GePG's answer to a cancellation or expiry-change request. */
public record ChangeResultMessage(String messageId, String gepgBillId, String kind, boolean accepted, String responseCode) {}
