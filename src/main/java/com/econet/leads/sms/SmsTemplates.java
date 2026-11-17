package com.econet.leads.sms;

import java.util.Map;
import java.util.Set;

/** Follow-up SMS templates (FR/EN). Placeholders: {businessName}, {callerName}, {companyName}. */
public final class SmsTemplates {

    public static final Set<String> KEYS = Set.of("missed_call", "after_voicemail", "quote_followup");
    public static final String STOP_FR = "Répondez STOP pour vous désabonner.";
    public static final String STOP_EN = "Reply STOP to opt out.";

    private static final Map<String, String> FR = Map.of(
            "missed_call", "Bonjour, ici {callerName} de {companyName}. J'ai essayé de joindre {businessName} au sujet de "
                    + "l'entretien ménager. Rappelez-moi quand cela vous convient.",
            "after_voicemail", "Bonjour, ici {callerName} de {companyName}. Je vous ai laissé un message vocal au sujet de "
                    + "l'entretien ménager de {businessName}. Une évaluation gratuite et sans engagement vous intéresse?",
            "quote_followup", "Bonjour, ici {callerName} de {companyName}. Je fais suite à notre soumission pour "
                    + "{businessName}. Avez-vous des questions? Je suis disponible pour en discuter.");

    private static final Map<String, String> EN = Map.of(
            "missed_call", "Hello, this is {callerName} from {companyName}. I tried to reach {businessName} about "
                    + "commercial cleaning. Call me back whenever suits you.",
            "after_voicemail", "Hello, this is {callerName} from {companyName}. I left you a voicemail about cleaning "
                    + "for {businessName}. Would a free, no-obligation assessment interest you?",
            "quote_followup", "Hello, this is {callerName} from {companyName}. I'm following up on our quote for "
                    + "{businessName}. Any questions? I'm happy to go over it.");

    private SmsTemplates() {
    }

    /** Rendered body, always ending with the opt-out line. */
    public static String render(String key, String language, String businessName, String callerName, String companyName) {
        return render(key, language, businessName, callerName, companyName, null);
    }

    /**
     * Same, with the sender's contact line (CASL: a commercial message must identify the sender and
     * give a way to reach it, e.g. a phone number or website) placed before the opt-out line.
     */
    public static String render(String key, String language, String businessName, String callerName, String companyName,
                                String contact) {
        boolean en = "en".equals(language);
        String text = (en ? EN : FR).get(key)
                .replace("{businessName}", nz(businessName))
                .replace("{callerName}", nz(callerName))
                .replace("{companyName}", nz(companyName));
        return text + (contact == null || contact.isBlank() ? "" : " " + contact.strip()) + " " + (en ? STOP_EN : STOP_FR);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
