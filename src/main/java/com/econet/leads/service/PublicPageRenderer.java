package com.econet.leads.service;

import com.econet.leads.model.Quote;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-rendered pages of /public/**: minimal HTML, inline CSS in the brand green, no JavaScript,
 * every dynamic value escaped with {@link HtmlUtils#htmlEscape}. French or English by the language
 * of the quote / of the lead.
 */
@Component
public class PublicPageRenderer {

    static final String BRAND = "#3f6b5a";

    private final String companyName;

    public PublicPageRenderer(@Value("${app.company.name:EcoNet Propreté}") String companyName) {
        this.companyName = companyName;
    }

    // ------------------------------------------------------------------ quote

    public String quotePage(Quote q, String error) {
        boolean en = "en".equals(q.getLanguage());
        Locale locale = en ? Locale.CANADA : Locale.CANADA_FRENCH;
        DateTimeFormatter dates = DateTimeFormatter.ofPattern(en ? "MMMM d, yyyy" : "d MMMM yyyy", locale);
        String token = q.getToken();
        StringBuilder b = new StringBuilder();
        b.append("<h1>").append(en ? "Quote " : "Soumission ").append(esc(q.getNumber())).append("</h1>");
        b.append("<p class=\"muted\">").append(esc(q.getBusiness().getBusinessName())).append(" · ")
                .append(en ? "valid until " : "valide jusqu'au ").append(esc(dates.format(q.getValidUntil()))).append("</p>");

        switch (q.getStatus()) {
            case Quote.ACCEPTED -> b.append("<p class=\"notice ok\">").append(en
                    ? "Quote accepted" + (q.getAcceptedAt() != null ? " on " + dates.format(q.getAcceptedAt()) : "") + ". Thank you, we will contact you to schedule the first visit."
                    : "Soumission acceptée" + (q.getAcceptedAt() != null ? " le " + dates.format(q.getAcceptedAt()) : "") + ". Merci, nous vous contacterons pour planifier la première visite.")
                    .append("</p>");
            case Quote.DECLINED -> b.append("<p class=\"notice\">").append(en
                    ? "This quote was declined. If that was a mistake, reply to our email and we will help you."
                    : "Cette soumission a été refusée. Si c'est une erreur, répondez à notre courriel et nous vous aiderons.")
                    .append("</p>");
            case Quote.EXPIRED -> b.append("<p class=\"notice\">").append(en
                    ? "This quote has expired. Contact us and we will prepare a new one."
                    : "Cette soumission est expirée. Communiquez avec nous et nous en préparerons une nouvelle.")
                    .append("</p>");
            default -> {
            }
        }
        if (error != null) {
            b.append("<p class=\"notice err\" role=\"alert\">").append(esc(error)).append("</p>");
        }

        Map<String, Object> input = q.getInput();
        if (input != null) {
            b.append("<p class=\"muted\">").append(esc(QuoteLabels.service(str(input.get("service")), en))).append(" · ")
                    .append(esc(QuoteLabels.area(str(input.get("area")), en))).append(" · ")
                    .append(esc(QuoteLabels.propertyType(str(input.get("propertyType")), en))).append(" · ")
                    .append(esc(QuoteLabels.frequency(str(input.get("frequency")), en))).append("</p>");
        }
        b.append("<table><tbody>");
        for (Map<String, Object> line : q.getLines()) {
            double amount = ((Number) line.get("amount")).doubleValue();
            b.append("<tr><td>").append(esc(str(line.get("label")))).append("</td><td class=\"num\">")
                    .append(amount < 0 ? "-" : "").append(esc(EmailService.money(Math.abs(amount), locale))).append("</td></tr>");
        }
        b.append("<tr class=\"sum\"><td>").append(en ? "Subtotal" : "Sous-total").append("</td><td class=\"num\">")
                .append(esc(EmailService.money(q.getSubtotal(), locale))).append("</td></tr>");
        b.append("<tr><td>").append(en ? "Taxes (GST + QST 14.975%)" : "Taxes (TPS + TVQ 14,975 %)").append("</td><td class=\"num\">")
                .append(esc(EmailService.money(q.getTax(), locale))).append("</td></tr>");
        b.append("<tr class=\"total\"><td>Total</td><td class=\"num\">")
                .append(esc(EmailService.money(q.getTotal(), locale))).append("</td></tr>");
        b.append("</tbody></table>");

        b.append("<p><a class=\"btn secondary\" href=\"/public/quotes/").append(esc(token)).append("/pdf\">")
                .append(en ? "Download the PDF" : "Télécharger le PDF").append("</a></p>");

        if (Quote.DRAFT.equals(q.getStatus()) || Quote.SENT.equals(q.getStatus())) {
            b.append("<h2>").append(en ? "Accept this quote" : "Accepter cette soumission").append("</h2>");
            b.append("<form method=\"post\" action=\"/public/quotes/").append(esc(token)).append("/accept\">");
            b.append("<label for=\"fullName\">").append(en ? "Full name" : "Nom complet").append("</label>");
            b.append("<input id=\"fullName\" name=\"fullName\" type=\"text\" required minlength=\"2\" maxlength=\"200\" autocomplete=\"name\">");
            b.append("<label class=\"check\"><input type=\"checkbox\" name=\"agree\" value=\"yes\" required> ")
                    .append(en ? "I accept this quote and its conditions" : "J'accepte cette soumission et ses conditions").append("</label>");
            b.append("<button class=\"btn\" type=\"submit\">").append(en ? "Accept the quote" : "Accepter la soumission").append("</button>");
            b.append("</form>");
            b.append("<details><summary>").append(en ? "Decline this quote" : "Refuser cette soumission").append("</summary>");
            b.append("<form method=\"post\" action=\"/public/quotes/").append(esc(token)).append("/decline\">");
            b.append("<label for=\"reason\">").append(en ? "Reason (optional)" : "Raison (facultatif)").append("</label>");
            b.append("<textarea id=\"reason\" name=\"reason\" rows=\"3\" maxlength=\"500\"></textarea>");
            b.append("<button class=\"btn secondary\" type=\"submit\">").append(en ? "Decline" : "Refuser").append("</button>");
            b.append("</form></details>");
        }
        return page(en, (en ? "Quote " : "Soumission ") + q.getNumber(), b.toString());
    }

    // ------------------------------------------------------------------ booking

    public String bookingPage(BookingService.PublicView view, String error) {
        boolean en = "en".equals(view.language());
        Locale locale = en ? Locale.CANADA : Locale.CANADA_FRENCH;
        StringBuilder b = new StringBuilder();
        b.append("<h1>").append(en ? "Book your visit" : "Réservez votre visite").append("</h1>");
        b.append("<p class=\"muted\">").append(esc(view.leadName())).append("</p>");
        if (error != null) {
            b.append("<p class=\"notice err\" role=\"alert\">").append(esc(error)).append("</p>");
        }
        switch (view.state()) {
            case USED -> b.append("<p class=\"notice ok\">").append(en ? "Your visit is booked" : "Votre visite est réservée")
                    .append(view.bookedAt() != null ? " : " + esc(dayTime(view.bookedAt(), en)) : "")
                    .append(".</p><p>").append(en ? "A confirmation was sent by email when an address is on file. To change the time, reply to our email."
                            : "Une confirmation est envoyée par courriel lorsqu'une adresse est au dossier. Pour changer l'heure, répondez à notre courriel.")
                    .append("</p>");
            case EXPIRED -> b.append("<p class=\"notice\">").append(en
                    ? "This booking link has expired. Contact us and we will send you a new one."
                    : "Ce lien de réservation est expiré. Communiquez avec nous et nous vous en enverrons un nouveau.").append("</p>");
            default -> {
                if (view.slots().isEmpty()) {
                    b.append("<p class=\"notice\">").append(en
                            ? "No time is available in the next two weeks. Contact us and we will find a time together."
                            : "Aucune plage n'est disponible dans les deux prochaines semaines. Communiquez avec nous et nous trouverons un moment ensemble.")
                            .append("</p>");
                } else {
                    b.append("<p>").append(en ? "Pick a time for a free visit of about one hour. Times are Montreal time."
                            : "Choisissez une heure pour une visite gratuite d'environ une heure. Les heures sont celles de Montréal.").append("</p>");
                    Map<LocalDate, List<BookingService.Slot>> byDay = new LinkedHashMap<>();
                    view.slots().forEach(s -> byDay.computeIfAbsent(s.start().toLocalDate(), d -> new java.util.ArrayList<>()).add(s));
                    DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern(en ? "EEEE, MMMM d" : "EEEE d MMMM", locale);
                    DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern(en ? "h:mm a" : "H 'h' mm", locale);
                    for (var entry : byDay.entrySet()) {
                        b.append("<h2>").append(esc(capitalize(dayFmt.format(entry.getKey())))).append("</h2><div class=\"slots\">");
                        for (BookingService.Slot s : entry.getValue()) {
                            b.append("<form method=\"post\"><button class=\"btn slot\" type=\"submit\" name=\"slot\" value=\"")
                                    .append(esc(s.start().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")))).append("\">")
                                    .append(esc(timeFmt.format(s.start()))).append("</button></form>");
                        }
                        b.append("</div>");
                    }
                }
            }
        }
        return page(en, en ? "Book your visit" : "Réservez votre visite", b.toString());
    }

    // ------------------------------------------------------------------ generic

    public String messagePage(boolean en, String title, String message) {
        return page(en, title, "<h1>" + esc(title) + "</h1><p>" + esc(message) + "</p>");
    }

    public String notFoundPage() {
        return page(false, "Lien introuvable",
                "<h1>Lien introuvable</h1><p>Ce lien est invalide ou n'existe plus. / This link is invalid or no longer exists.</p>");
    }

    String page(boolean en, String title, String content) {
        return "<!doctype html><html lang=\"" + (en ? "en" : "fr") + "\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex, nofollow\">"
                + "<title>" + esc(title) + " – " + esc(companyName) + "</title><style>" + CSS + "</style></head><body>"
                + "<header><span class=\"brand\">" + esc(companyName) + "</span></header><main>" + content + "</main>"
                + "<footer>" + esc(companyName) + "</footer></body></html>";
    }

    private static final String CSS = ":root{--brand:" + BRAND + ";--ink:#1d2a25;--muted:#5f6b66;--line:#d9e1dd;--bg:#f6f8f7}"
            + "*{box-sizing:border-box}body{margin:0;font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;color:var(--ink);background:var(--bg)}"
            + "header{background:var(--brand);color:#fff;padding:14px 16px}.brand{font-weight:600;font-size:18px}"
            + "main{max-width:640px;margin:0 auto;padding:20px 16px 40px;background:#fff;min-height:70vh}"
            + "h1{font-size:24px;margin:8px 0 4px}h2{font-size:18px;margin:24px 0 8px}.muted{color:var(--muted);margin:4px 0 12px}"
            + "table{width:100%;border-collapse:collapse;margin:12px 0}td{padding:8px 0;border-bottom:1px solid var(--line)}"
            + "td.num{text-align:right;white-space:nowrap;padding-left:12px}tr.sum td{font-weight:600}tr.total td{font-size:18px;font-weight:700;color:var(--brand);border-bottom:0}"
            + "label{display:block;margin:12px 0 4px;font-weight:600}label.check{font-weight:400;display:flex;gap:8px;align-items:flex-start}"
            + "input[type=text],textarea{width:100%;padding:10px;border:1px solid #b9c4be;border-radius:8px;font:inherit}"
            + "input[type=checkbox]{margin-top:5px;width:18px;height:18px}"
            + ".btn{display:inline-block;margin-top:14px;padding:12px 18px;border:0;border-radius:8px;background:var(--brand);color:#fff;font:inherit;font-weight:600;cursor:pointer;text-decoration:none;min-height:44px}"
            + ".btn.secondary{background:#fff;color:var(--brand);border:1px solid var(--brand)}"
            + ".slots{display:flex;flex-wrap:wrap;gap:8px}.slots form{margin:0}.btn.slot{margin-top:0;background:#fff;color:var(--brand);border:1px solid var(--brand)}"
            + ".btn:focus-visible,input:focus-visible,textarea:focus-visible,summary:focus-visible{outline:3px solid #8fb3a4;outline-offset:2px}"
            + ".notice{padding:12px;border:1px solid var(--line);border-radius:8px;background:var(--bg)}.notice.ok{border-color:var(--brand)}.notice.err{border-color:#b3261e;color:#7a1712}"
            + "details{margin-top:20px}summary{cursor:pointer;color:var(--muted)}footer{max-width:640px;margin:0 auto;padding:16px;color:var(--muted);font-size:14px}";

    private static String dayTime(LocalDateTime t, boolean en) {
        Locale locale = en ? Locale.CANADA : Locale.CANADA_FRENCH;
        return capitalize(DateTimeFormatter.ofPattern(en ? "EEEE, MMMM d 'at' h:mm a" : "EEEE d MMMM 'à' H 'h' mm", locale).format(t));
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    static String esc(String s) {
        return HtmlUtils.htmlEscape(s == null ? "" : s, "UTF-8");
    }
}
