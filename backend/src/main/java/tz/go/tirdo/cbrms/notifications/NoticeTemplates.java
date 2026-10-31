package tz.go.tirdo.cbrms.notifications;

import java.util.Map;

/**
 * Client notice wording in Kiswahili and English (FR31, NFR08). Kept short to fit one SMS.
 * Finance and TIRDO communications must approve the final wording before go-live (AT18).
 */
public final class NoticeTemplates {
    private NoticeTemplates() {}

    private static final Map<String, Map<String, String>> TEMPLATES = Map.of(
            "CONTROL_NUMBER_ISSUED", Map.of(
                    "sw", "TIRDO: Namba ya malipo {cn} kwa {description}. Kiasi TZS {amount}. Lipa kabla ya {due}. Kumb: {ref}.",
                    "en", "TIRDO: Control number {cn} for {description}. Amount TZS {amount}. Pay by {due}. Ref: {ref}."),
            "PAYMENT_RECEIVED", Map.of(
                    "sw", "TIRDO: Tumepokea TZS {amount} kwa namba ya malipo {cn}. Salio TZS {outstanding}. Asante.",
                    "en", "TIRDO: We received TZS {amount} for control number {cn}. Balance TZS {outstanding}. Thank you."),
            "BILL_CANCELLED", Map.of(
                    "sw", "TIRDO: Bili yenye namba ya malipo {cn} imefutwa. Usilipe kwa namba hii.",
                    "en", "TIRDO: The bill with control number {cn} has been cancelled. Do not pay with this number."),
            "EXPIRY_CHANGED", Map.of(
                    "sw", "TIRDO: Muda wa kulipa namba ya malipo {cn} umeongezwa hadi {due}.",
                    "en", "TIRDO: The payment deadline for control number {cn} is now {due}."),
            "LOOKUP_CODE", Map.of(
                    "sw", "TIRDO: Namba yako ya uthibitisho ni {code}. Inaisha baada ya dakika 5. Usimpe mtu yeyote.",
                    "en", "TIRDO: Your verification code is {code}. It expires in 5 minutes. Do not share it."));

    public static String render(String template, String language, Map<String, String> values) {
        Map<String, String> byLanguage = TEMPLATES.get(template);
        if (byLanguage == null) {
            throw new IllegalArgumentException("Unknown notice template " + template);
        }
        String text = byLanguage.getOrDefault(language, byLanguage.get("sw"));
        for (Map.Entry<String, String> e : values.entrySet()) {
            text = text.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return text;
    }
}
