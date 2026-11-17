package com.econet.leads.sms;

import com.econet.leads.config.MailSettings;
import com.econet.leads.integration.website.InboundLeadEvent;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owner alerts for each new inbound website lead: an SMS to every ALERT_PHONE and an email to every
 * ALERT_EMAIL (when SMTP is configured). At most one alert SMS per lead and 30 per day. Runs on its
 * own thread and swallows every failure, so an import is never held up or broken by an alert.
 */
@Service
@Slf4j
public class AlertService {

    /** What {@link #sendTest()} did, per channel. */
    public record TestResult(int smsTargets, int smsSent, int emailTargets, int emailSent, boolean smsConfigured,
                             boolean emailConfigured) {
    }

    private final SmsService smsService;
    private final SmsMessageRepository messages;
    private final AlertSettings alerts;
    private final MailSettings mailSettings;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final Clock clock;
    private final String crmUrl;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "owner-alerts");
        t.setDaemon(true);
        return t;
    });

    public AlertService(SmsService smsService, SmsMessageRepository messages, AlertSettings alerts,
                        MailSettings mailSettings, ObjectProvider<JavaMailSender> mailSender, Clock clock,
                        @Value("${app.crm-url:http://localhost:5173}") String crmUrl) {
        this.smsService = smsService;
        this.messages = messages;
        this.alerts = alerts;
        this.mailSettings = mailSettings;
        this.mailSender = mailSender;
        this.clock = clock;
        this.crmUrl = crmUrl == null ? "" : crmUrl.replaceAll("/+$", "");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInbound(InboundLeadEvent event) {
        if (alerts.phones().isEmpty() && alerts.emails().isEmpty()) {
            return;
        }
        try {
            executor.execute(() -> handle(event));
        } catch (RuntimeException e) {
            log.warn("Owner alert could not be queued for lead {}: {}", event.leadId(), e.toString());
        }
    }

    /** Synchronous body of the listener (public for tests). Never throws. */
    public void handle(InboundLeadEvent event) {
        if (event.backlog()) {
            log.debug("Lead {} is an old request imported late: no owner alert", event.leadId());
            return;
        }
        try {
            sendSms(event);
        } catch (RuntimeException e) {
            log.warn("Owner SMS alert failed for lead {}: {}", event.leadId(), e.toString());
        }
        try {
            sendEmail(event);
        } catch (RuntimeException e) {
            log.warn("Owner email alert failed for lead {}: {}", event.leadId(), e.toString());
        }
    }

    // ------------------------------------------------------------------ SMS

    private void sendSms(InboundLeadEvent event) {
        List<String> phones = alerts.phones();
        if (phones.isEmpty() || !smsService.isConfigured()) {
            return;
        }
        if (messages.existsByBusinessIdAndKind(event.leadId(), SmsMessage.ALERT)) {
            log.debug("Alert SMS already sent for lead {}", event.leadId());
            return;
        }
        String body = smsBody(event);
        for (String phone : phones) {
            long today = messages.countByKindAndBusinessIdIsNotNullAndCreatedAtGreaterThanEqual(SmsMessage.ALERT, LocalDate.now(clock).atStartOfDay());
            if (today >= Math.max(1, alerts.getMaxSmsPerDay())) {
                log.warn("Daily limit of {} alert SMS reached; skipping lead {}", alerts.getMaxSmsPerDay(), event.leadId());
                return;
            }
            String to = SmsService.toE164(phone);
            if (to == null) {
                log.warn("ALERT_PHONE entry {} is not a valid phone number", SmsService.mask(phone));
                continue;
            }
            record(event.leadId(), null, to, SmsMessage.ALERT, smsService.send(to, body), null);
        }
    }

    String smsBody(InboundLeadEvent e) {
        List<String> parts = new ArrayList<>();
        parts.add(e.leadName() == null || e.leadName().isBlank() ? "client inconnu" : e.leadName().strip());
        if (e.businessType() != null && !e.businessType().isBlank()) {
            parts.add(e.businessType().strip().replaceFirst("^Demande web – ", ""));
        }
        if (e.estimatedValue() != null && e.estimatedValue().signum() > 0) {
            parts.add(e.estimatedValue().stripTrailingZeros().toPlainString() + " $");
        }
        String phone = e.phone() == null || e.phone().isBlank() ? "numéro inconnu" : e.phone().strip();
        return "EcoNet: nouvelle demande du site — " + String.join(", ", parts) + ". Appeler: " + phone + ". "
                + crmUrl + "/call?leadId=" + e.leadId();
    }

    /** Stores the log row of a send (kind alert or follow_up). */
    SmsMessage record(java.util.UUID businessId, java.util.UUID userId, String toE164, String kind, SmsService.Result result, String body) {
        SmsMessage m = new SmsMessage();
        m.setBusinessId(businessId);
        m.setUserId(userId);
        m.setToMasked(SmsService.mask(toE164));
        m.setKind(kind);
        m.setStatus(result.sent() ? SmsMessage.SENT : SmsMessage.FAILED);
        m.setProviderSid(result.sid());
        m.setError(SmsService.truncate(result.error(), 500));
        m.setBody(body == null ? null : SmsService.truncate(body, 300));
        return messages.save(m);
    }

    // ------------------------------------------------------------------ email

    private void sendEmail(InboundLeadEvent event) {
        List<String> targets = alerts.emails();
        JavaMailSender sender = emailReady() ? mailSender.getIfAvailable() : null;
        if (targets.isEmpty() || sender == null) {
            return;
        }
        String subject = "Nouvelle demande du site — " + (event.leadName() == null ? "client" : event.leadName());
        StringBuilder text = new StringBuilder("Une nouvelle demande vient d'arriver du site web.\n\n");
        text.append("Nom : ").append(nz(event.leadName())).append('\n');
        if (event.businessType() != null) text.append("Type : ").append(event.businessType()).append('\n');
        if (event.estimatedValue() != null && event.estimatedValue().signum() > 0) {
            text.append("Estimation : ").append(event.estimatedValue().stripTrailingZeros().toPlainString()).append(" $\n");
        }
        if (event.phone() != null && !event.phone().isBlank()) text.append("Téléphone : ").append(event.phone()).append('\n');
        text.append("\nOuvrir dans le CRM : ").append(crmUrl).append("/call?leadId=").append(event.leadId()).append('\n');
        for (String to : targets) {
            sendMail(sender, to, subject, text.toString());
        }
    }

    private boolean emailReady() {
        return mailSettings.isConfigured();
    }

    private boolean sendMail(JavaMailSender sender, String to, String subject, String text) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(mailSettings.effectiveFrom(), mailSettings.getFromName());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, false);
            sender.send(message);
            return true;
        } catch (Exception e) {
            log.warn("Alert email failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    // ------------------------------------------------------------------ test alert

    /** Sends a clearly labelled test to every alert target. Test rows have no lead, so they do not use up the daily alert budget. */
    public TestResult sendTest() {
        List<String> phones = alerts.phones();
        List<String> emails = alerts.emails();
        boolean smsOk = smsService.isConfigured();
        JavaMailSender sender = emailReady() ? mailSender.getIfAvailable() : null;
        int smsSent = 0;
        if (smsOk) {
            for (String phone : phones) {
                String to = SmsService.toE164(phone);
                if (to == null) continue;
                SmsService.Result r = smsService.send(to, "EcoNet: alerte test. Les alertes de nouvelles demandes du site arriveront ici. "
                        + crmUrl);
                record(null, null, to, SmsMessage.ALERT, r, null);
                if (r.sent()) smsSent++;
            }
        }
        int mailSent = 0;
        if (sender != null) {
            for (String to : emails) {
                if (sendMail(sender, to, "Alerte test EcoNet", "Ceci est une alerte test. Les nouvelles demandes du site seront envoyées à cette adresse.\n"
                        + crmUrl + "\n")) {
                    mailSent++;
                }
            }
        }
        return new TestResult(phones.size(), smsSent, emails.size(), mailSent, smsOk, sender != null);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
