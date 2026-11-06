package com.econet.leads.integration.website;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Read-only connection to the public website's Postgres (Supabase session pooler).
 *
 * The pool exists only when {@code app.website.db.url} (env WEBSITE_DB_URL) is set. It is NOT
 * exposed as a {@code DataSource} bean on purpose: a second DataSource bean would switch off Spring
 * Boot's auto-configuration of the application's own database. At most 2 connections, opened
 * lazily; an unreachable website database never prevents the application from starting.
 *
 * Nothing in this application ever writes to this database: connections are read-only (the
 * PostgreSQL driver is told {@code readOnlyMode=always}) and only SELECT statements are issued.
 */
@Component
@Slf4j
public class WebsiteDb {

    private final String rawUrl;
    private final String rawUsername;
    private final String rawPassword;
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;

    public WebsiteDb(@Value("${app.website.db.url:}") String url,
                     @Value("${app.website.db.username:}") String username,
                     @Value("${app.website.db.password:}") String password) {
        this.rawUrl = url == null ? "" : url.trim();
        this.rawUsername = username == null ? "" : username.trim();
        this.rawPassword = password == null ? "" : password;
    }

    @PostConstruct
    void init() {
        if (rawUrl.isEmpty()) {
            log.info("Website requests: WEBSITE_DB_URL is not set, the website source stays unconfigured");
            return;
        }
        String jdbcUrl = jdbcUrl(rawUrl);
        String username = rawUsername;
        String password = rawPassword;
        // postgresql://user:pass@host/db style (as copied from Supabase): take the credentials from the URL
        String[] userInfo = userInfo(rawUrl);
        if (userInfo != null) {
            if (username.isEmpty()) username = userInfo[0];
            if (password.isEmpty() && userInfo[1] != null) password = userInfo[1];
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("website-readonly");
        config.setJdbcUrl(jdbcUrl);
        if (!username.isEmpty()) config.setUsername(username);
        if (!password.isEmpty()) config.setPassword(password);
        config.setReadOnly(true);
        config.setAutoCommit(false);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(10_000);
        config.setInitializationFailTimeout(-1); // lazy: do not fail startup when unreachable
        if (jdbcUrl.startsWith("jdbc:postgresql:")) {
            config.addDataSourceProperty("readOnlyMode", "always");
        }
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(30);
        log.info("Website requests: read-only pool ready for {}", redact(jdbcUrl));
    }

    @PreDestroy
    void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    public boolean isConfigured() {
        return jdbc != null;
    }

    /** Read-only JDBC access; only call when {@link #isConfigured()}. */
    public JdbcTemplate jdbc() {
        if (jdbc == null) {
            throw new IllegalStateException("Website database is not configured (set WEBSITE_DB_URL)");
        }
        return jdbc;
    }

    // ------------------------------------------------------------------ url handling

    /** jdbc: URLs are used as is; postgres(ql)://user:pass@host:port/db?x=y becomes jdbc:postgresql://host:port/db?x=y. */
    static String jdbcUrl(String url) {
        if (url.startsWith("jdbc:")) {
            return url;
        }
        if (url.startsWith("postgres://") || url.startsWith("postgresql://")) {
            URI uri = URI.create(url);
            StringBuilder sb = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
            if (uri.getPort() > 0) sb.append(':').append(uri.getPort());
            sb.append(uri.getRawPath() == null ? "" : uri.getRawPath());
            if (uri.getRawQuery() != null) sb.append('?').append(uri.getRawQuery());
            return sb.toString();
        }
        return url;
    }

    static String[] userInfo(String url) {
        if (!(url.startsWith("postgres://") || url.startsWith("postgresql://"))) {
            return null;
        }
        String raw = URI.create(url).getRawUserInfo();
        if (raw == null) {
            return null;
        }
        int colon = raw.indexOf(':');
        String user = URLDecoder.decode(colon < 0 ? raw : raw.substring(0, colon), StandardCharsets.UTF_8);
        String pass = colon < 0 ? null : URLDecoder.decode(raw.substring(colon + 1), StandardCharsets.UTF_8);
        return new String[]{user, pass};
    }

    private static String redact(String jdbcUrl) {
        int q = jdbcUrl.indexOf('?');
        return q < 0 ? jdbcUrl : jdbcUrl.substring(0, q);
    }
}
