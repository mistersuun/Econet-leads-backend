package com.econet.leads.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * The JavaMailSender exists only when SMTP_HOST is set (Spring's MailSenderAutoConfiguration is
 * excluded in {@link com.econet.leads.EconetLeadsApplication} so an empty host never creates one).
 * Without it, GET /api/email/status reports configured: false and sends return 503.
 */
@Configuration
public class MailConfig {

    @Bean
    @ConditionalOnExpression("!'${app.mail.host:}'.isEmpty()")
    public JavaMailSender javaMailSender(MailSettings settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.getHost());
        sender.setPort(settings.getPort());
        sender.setDefaultEncoding("UTF-8");
        boolean auth = settings.getUsername() != null && !settings.getUsername().isBlank();
        if (auth) {
            sender.setUsername(settings.getUsername());
            sender.setPassword(settings.getPassword());
        }
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", Boolean.toString(auth));
        props.put("mail.smtp.starttls.enable", Boolean.toString(settings.isStarttls()));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");
        return sender;
    }
}
