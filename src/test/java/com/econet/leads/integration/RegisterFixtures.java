package com.econet.leads.integration;

import com.econet.leads.model.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds register ZIPs from src/test/resources/fixtures/registre/*.csv and loads the production
 * config of the source straight from the V8 migration, so tests exercise the shipped config.
 *
 * <p>Header provenance of the fixture CSVs:
 * <ul>
 *   <li>Etablissements.csv: exact header captured from the real file on 2026-08-05
 *       (github.com/MoKarade/JobAI tests/registre.test.ts), including the UTF-8 BOM;</li>
 *   <li>Entreprise.csv, Nom.csv, FusionScissions.csv, ContinuationsTransformations.csv,
 *       DomaineValeur.csv: column lists from guide IN-537 as transcribed by
 *       github.com/ianleelet-max/Slotmachine packages/ingestion/src/specification.ts, matching the
 *       MySQL schema of github.com/TsioryRaphael/Sraping-python import_csv_to_mysql.py;</li>
 *   <li>code values: COD_STAT_IMMAT "IM" = immatriculée and STAT_NOM "V" from real-data analyses
 *       (github.com/goneau/esMauricie site_data/req.qmd, github.com/yvan-003/analyse-entreprises-quebec-sql);
 *       DomaineValeur type "INTVAL_EMPLO_QUE" and its labels are a guess by analogy with "STAT_IMMAT".</li>
 * </ul>
 * File names inside the ZIP (Entreprise.csv, Etablissements.csv, Nom.csv, FusionScissions.csv,
 * ContinuationsTransformations.csv, DomaineValeur.csv) are from the same sources.
 */
final class RegisterFixtures {

    static final List<String> FILES = List.of("Entreprise.csv", "Etablissements.csv", "Nom.csv", "FusionScissions.csv",
            "ContinuationsTransformations.csv", "DomaineValeur.csv");

    private RegisterFixtures() {
    }

    static Path zipFixtures(Path dir, String... skip) throws IOException {
        Path zip = Files.createTempFile(dir, "registre-", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String f : FILES) {
                if (List.of(skip).contains(f)) continue;
                out.putNextEntry(new ZipEntry(f));
                try (InputStream in = RegisterFixtures.class.getResourceAsStream("/fixtures/registre/" + f)) {
                    in.transferTo(out);
                }
                out.closeEntry();
            }
        }
        return zip;
    }

    static void zipEntry(ZipOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    static OutputStream entry(ZipOutputStream out, String name) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        return out;
    }

    /** Config JSON of a data source as inserted by V8 (by source name). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> v8Config(String sourceName) throws IOException {
        String sql;
        try (InputStream in = RegisterFixtures.class.getResourceAsStream("/db/migration/V8__lead_sources_tenders.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String quotedName = "'" + sourceName.replace("'", "''") + "'";
        int at = sql.indexOf(quotedName);
        if (at < 0) throw new IllegalStateException("No source " + sourceName + " in V8");
        Matcher m = Pattern.compile("'(\\{.*?\\})',\\s*CURRENT_TIMESTAMP", Pattern.DOTALL).matcher(sql);
        if (!m.find(at)) throw new IllegalStateException("No config for " + sourceName);
        String json = m.group(1).replace("''", "'");
        return new ObjectMapper().readValue(json, Map.class);
    }

    static DataSource source(String name, DataSource.SourceType type) throws IOException {
        DataSource ds = new DataSource();
        ds.setSourceName(name);
        ds.setSourceType(type);
        ds.setActive(true);
        ds.setConfig(v8Config(name));
        return ds;
    }
}
