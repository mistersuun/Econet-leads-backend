package com.econet.leads.integration.fileimport;

import com.econet.leads.dto.ImportRunRequest;
import com.econet.leads.exception.ApiException;
import com.econet.leads.integration.ImportJobRunner;
import com.econet.leads.integration.ImportReport;
import com.econet.leads.integration.ImportService;
import com.econet.leads.integration.support.ImportFiles;
import com.econet.leads.model.Business;
import com.econet.leads.model.DataSource;
import com.econet.leads.model.ScraperJob;
import com.econet.leads.model.User;
import com.econet.leads.repository.DataSourceRepository;
import com.econet.leads.service.BusinessService;
import com.econet.leads.util.PhoneFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * File imports: upload (inspect + suggest a mapping), then run the mapped rows through the normal
 * import job runner (one background job, find-or-create per row, per-row failure isolation).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileImportService {

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final String SOURCE_PREFIX = "Import – ";
    static final int MAX_REASONS = 50;
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final ImportFileStore store;
    private final ImportService importService;
    private final ImportJobRunner jobRunner;
    private final BusinessService businessService;
    private final DataSourceRepository dataSourceRepository;

    // ------------------------------------------------------------------ upload

    /** 400 unsupported/empty/unreadable file, 413 over 10 MB. */
    public ImportFileStore.UploadInfo upload(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Champ multipart 'file' manquant ou vide");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Fichier trop volumineux (maximum 10 Mo)");
        }
        String fileName = file.getOriginalFilename() == null ? "import" : Path.of(file.getOriginalFilename()).getFileName().toString();
        TabularFile.Kind kind = TabularFile.kindOf(fileName);

        Path target = ImportFiles.newTempFile("upload-file-", kind == TabularFile.Kind.XLSX ? ".xlsx" : ".txt");
        try {
            file.transferTo(target);
            if (kind == TabularFile.Kind.XLSX && !startsWithZipMagic(target)) {
                throw ApiException.badRequest("Ce fichier n'est pas un classeur .xlsx valide");
            }
            TabularFile.Layout layout = TabularFile.inspect(target, fileName, 5);
            ImportFileStore.Entry entry = store.put(target, fileName, layout);
            log.info("Import upload {} ({}, {} rows, {})", entry.id(), fileName, layout.rowCount(), layout.kind());
            return entry.info();
        } catch (IOException | RuntimeException e) {
            ImportFiles.deleteQuietly(target);
            throw e;
        }
    }

    public ImportFileStore.UploadInfo info(String importId) {
        return store.get(importId).orElseThrow(() -> notFound(importId)).info();
    }

    // ------------------------------------------------------------------ run

    /**
     * Starts the background job: 400 bad mapping/defaults, 404 unknown or expired upload, 409 already
     * running (this upload or a job of the same "Import – name" source).
     */
    public ScraperJob run(String importId, ImportRunRequest request, User caller) {
        ImportFileStore.Entry entry = store.get(importId).orElseThrow(() -> notFound(importId));
        Plan plan = plan(entry, request);

        if (!entry.tryStart()) {
            throw ApiException.conflict("Cet import est déjà en cours d'exécution");
        }
        try {
            DataSource source = sourceFor(plan.dataSourceName);
            ImportReport report = new ImportReport(MAX_REASONS + 10);
            Counts counts = new Counts();
            User assignee = Boolean.TRUE.equals(request.getAssignToMe()) ? caller : null;
            boolean skipDuplicates = Boolean.TRUE.equals(request.getSkipDuplicates());
            report.onComplete(t -> {
                if (counts.invalid > MAX_REASONS) {
                    report.note("… et " + (counts.invalid - MAX_REASONS) + " autres lignes invalides");
                }
                report.prepend(String.format(Locale.ROOT,
                    "Résumé: %d lignes lues · %d ajoutés · %d mis à jour · %d ignorés (doublons) · %d invalides · %d erreurs",
                    counts.read, t.added(), t.updated(), counts.skipped, counts.invalid, t.failed()));
            });

            ScraperJob job = importService.startTask(source, jobId -> {
                try {
                    jobRunner.runBusinesses(jobId, source.getId(), source.getSourceName(),
                            sink -> readRows(entry, plan, assignee, skipDuplicates, counts, report, sink), report);
                } finally {
                    store.remove(entry.id());
                }
            });
            return job;
        } catch (RuntimeException e) {
            entry.finish();
            throw e;
        }
    }

    /** Streams the valid, non-skipped rows of the file as Business records. */
    private void readRows(ImportFileStore.Entry entry, Plan plan, User assignee, boolean skipDuplicates,
                          Counts counts, ImportReport report, java.util.function.Predicate<Business> sink) throws IOException {
        boolean[] headerSeen = {false};
        TabularFile.read(entry.file(), entry.layout().kind(), entry.layout().delimiter(), (recordNo, cells) -> {
            if (TabularFile.isBlank(cells)) {
                return true;
            }
            if (!headerSeen[0]) {
                headerSeen[0] = true;
                return true;
            }
            counts.read++;
            Business business = toBusiness(entry.id(), recordNo, cells, plan);
            String reason = invalidReason(business);
            if (reason != null) {
                counts.invalid++;
                if (counts.invalid <= MAX_REASONS) {
                    report.note("Ligne " + recordNo + " invalide: " + reason);
                }
                return true;
            }
            if (assignee != null) {
                business.setAssignedTo(assignee);
            }
            if (skipDuplicates && businessService.existsDuplicate(business)) {
                counts.skipped++;
                return true;
            }
            return sink.test(business);
        });
    }

    // ------------------------------------------------------------------ mapping

    private record Plan(String dataSourceName, String fileName, Map<String, Integer> columnOf, String defaultType,
                        String defaultCity) {
    }

    private static final class Counts {
        volatile int read;
        volatile int invalid;
        volatile int skipped;
    }

    private Plan plan(ImportFileStore.Entry entry, ImportRunRequest request) {
        if (request == null || request.getMapping() == null) {
            throw ApiException.badRequest("mapping est requis");
        }
        List<String> columns = entry.layout().columns();
        Map<String, Integer> columnOf = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : request.getMapping().entrySet()) {
            if (!HeaderSynonyms.FIELDS.contains(e.getKey())) {
                throw ApiException.badRequest("Champ de mapping inconnu '" + e.getKey() + "' (attendu : "
                        + String.join(", ", HeaderSynonyms.FIELDS) + ")");
            }
            if (e.getValue() == null || e.getValue().isBlank()) {
                continue;
            }
            int index = columns.indexOf(e.getValue());
            if (index < 0) {
                for (int i = 0; i < columns.size() && index < 0; i++) {
                    if (columns.get(i).equalsIgnoreCase(e.getValue().trim())) index = i;
                }
            }
            if (index < 0) {
                throw ApiException.badRequest("Colonne introuvable dans le fichier : '" + e.getValue() + "'");
            }
            columnOf.put(e.getKey(), index);
        }
        if (!columnOf.containsKey("businessName")) {
            throw ApiException.badRequest("mapping.businessName est requis");
        }
        ImportRunRequest.Defaults defaults = request.getDefaults();
        String sourceName = defaults == null || defaults.getSourceName() == null ? "" : defaults.getSourceName().strip();
        if (sourceName.isEmpty()) {
            throw ApiException.badRequest("defaults.sourceName est requis");
        }
        if (sourceName.length() > 80) {
            throw ApiException.badRequest("defaults.sourceName ne doit pas dépasser 80 caractères");
        }
        return new Plan(SOURCE_PREFIX + sourceName, entry.info().fileName(), columnOf,
                blankToNull(defaults.getBusinessType()), blankToNull(defaults.getAddressCity()));
    }

    private Business toBusiness(String importId, int recordNo, String[] cells, Plan plan) {
        Business b = new Business();
        String name = cell(cells, plan, "businessName");
        b.setBusinessName(name == null ? null : truncate(name, 255));
        b.setBusinessType(firstNonNull(truncate(cell(cells, plan, "businessType"), 100), truncate(plan.defaultType, 100), "Autre"));
        b.setAddressStreet(truncate(cell(cells, plan, "addressStreet"), 255));
        b.setAddressCity(firstNonNull(truncate(cell(cells, plan, "addressCity"), 100), truncate(plan.defaultCity, 100)));
        String postal = cell(cells, plan, "postalCode");
        b.setPostalCode(postal != null && postal.replaceAll("\\s", "").length() <= 7 ? postal : null);
        b.setPhone(phone(cell(cells, plan, "phone")));
        String email = cell(cells, plan, "email");
        b.setEmail(email != null && EMAIL.matcher(email).matches() && email.length() <= 255 ? email : null);
        b.setWebsite(truncate(cell(cells, plan, "website"), 500));
        b.setDataSource(plan.dataSourceName);
        b.setExternalId(importId + ":" + recordNo);

        Map<String, String> details = new LinkedHashMap<>();
        details.put("Fichier", plan.fileName);
        details.put("Ligne", Integer.toString(recordNo));
        String notes = cell(cells, plan, "notes");
        if (notes != null) {
            details.put("Notes", truncate(notes, 1000));
        }
        String decisionMaker = cell(cells, plan, "decisionMaker");
        if (decisionMaker != null) {
            details.put("Contact", truncate(decisionMaker, 255));
            Map<String, Object> qualification = new LinkedHashMap<>();
            qualification.put("decisionMaker", truncate(decisionMaker, 255));
            b.setQualification(qualification);
        }
        b.setSourceDetails(details);
        return b;
    }

    /** null when the row can be imported: a name and at least a phone, an email or an address. */
    static String invalidReason(Business b) {
        if (b.getBusinessName() == null || b.getBusinessName().isBlank()) {
            return "nom manquant";
        }
        boolean contactable = (b.getPhone() != null && !b.getPhone().isBlank())
                || (b.getEmail() != null && !b.getEmail().isBlank())
                || (b.getAddressStreet() != null && !b.getAddressStreet().isBlank());
        return contactable ? null : "ni téléphone, ni courriel, ni adresse";
    }

    /** A usable phone (10/11 digits, kept as typed) or null; anything that cannot fit the column is dropped. */
    private static String phone(String raw) {
        if (raw == null) return null;
        if (PhoneFormatter.isValid(raw)) return raw;
        return raw.length() <= 20 && raw.matches(".*\\d.*") ? raw : null;
    }

    private static String cell(String[] cells, Plan plan, String field) {
        Integer index = plan.columnOf.get(field);
        if (index == null || index >= cells.length) return null;
        return blankToNull(cells[index]);
    }

    private DataSource sourceFor(String name) {
        return dataSourceRepository.findBySourceName(name).orElseGet(() -> {
            DataSource ds = new DataSource();
            ds.setSourceName(name);
            ds.setSourceType(DataSource.SourceType.MANUAL_FILE);
            ds.setSyncFrequency(DataSource.SyncFrequency.MANUAL);
            ds.setActive(false); // never part of a scheduled "import all"
            ds.setConfig(Map.of("importer", "MANUAL_FILE"));
            return dataSourceRepository.save(ds);
        });
    }

    private static boolean startsWithZipMagic(Path file) throws IOException {
        try (var in = java.nio.file.Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(2);
            return magic.length == 2 && magic[0] == 'P' && magic[1] == 'K';
        }
    }

    private static ApiException notFound(String importId) {
        return ApiException.notFound("Import introuvable ou expiré : " + importId);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }

}
