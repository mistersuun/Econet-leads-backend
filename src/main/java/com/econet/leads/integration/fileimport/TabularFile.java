package com.econet.leads.integration.fileimport;

import com.econet.leads.exception.ApiException;
import com.econet.leads.integration.support.CsvSupport;
import com.opencsv.CSVReader;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Reads an uploaded table: delimited text (.csv, .tsv, .txt: delimiter sniffed among comma,
 * semicolon, tab and pipe; UTF-8 with or without BOM, windows-1252 when the bytes are not valid
 * UTF-8, which is what Excel's "CSV" export produces in French locales) or the first sheet of an
 * .xlsx workbook. The first non-empty record is the header row.
 *
 * Record numbers count physical records with the header row as 1, so they match the line / row
 * number the user sees in Excel; empty records are skipped by the readers' callers.
 */
public final class TabularFile {

    public static final int MAX_ROWS = 100_000;
    static final char[] DELIMITERS = {',', ';', '\t', '|'};
    private static final int SNIFF_LINES = 20;

    public enum Kind { TEXT, XLSX }

    /** What {@link #inspect} found. */
    public record Layout(Kind kind, char delimiter, List<String> columns, int rowCount, List<List<String>> sampleRows) {
    }

    private TabularFile() {
    }

    // ------------------------------------------------------------------ inspection

    /** Parses the file once: header, number of non-empty data rows and the first {@code sampleSize} of them. */
    public static Layout inspect(Path file, String fileName, int sampleSize) throws IOException {
        Kind kind = kindOf(fileName);
        char delimiter = kind == Kind.TEXT ? sniffDelimiter(Files.readAllBytes(file)) : ',';
        List<String> columns = new ArrayList<>();
        List<List<String>> sample = new ArrayList<>();
        int[] rows = {0};
        read(file, kind, delimiter, (recordNo, cells) -> {
            if (isBlank(cells)) {
                return true;
            }
            if (columns.isEmpty()) {
                columns.addAll(headerNames(cells));
                return true;
            }
            if (++rows[0] > MAX_ROWS) {
                throw ApiException.badRequest("Le fichier dépasse " + MAX_ROWS + " lignes");
            }
            if (sample.size() < sampleSize) {
                sample.add(pad(cells, columns.size()));
            }
            return true;
        });
        if (columns.isEmpty()) {
            throw ApiException.badRequest("Le fichier est vide : une ligne d'en-têtes est requise");
        }
        if (rows[0] == 0) {
            throw ApiException.badRequest("Le fichier ne contient aucune ligne de données sous les en-têtes");
        }
        return new Layout(kind, delimiter, List.copyOf(columns), rows[0], List.copyOf(sample));
    }

    public static Kind kindOf(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx")) {
            return Kind.XLSX;
        }
        if (name.endsWith(".csv") || name.endsWith(".tsv") || name.endsWith(".txt")) {
            return Kind.TEXT;
        }
        throw ApiException.badRequest("Format non supporté : utilisez .csv, .tsv, .txt ou .xlsx");
    }

    // ------------------------------------------------------------------ reading

    /**
     * Streams every physical record (header included, record number 1-based) to {@code sink}; the
     * sink returns false to stop. Cells are never null.
     */
    public static void read(Path file, Kind kind, char delimiter, BiPredicate<Integer, String[]> sink) throws IOException {
        try {
            if (kind == Kind.XLSX) {
                readXlsx(file, sink);
            } else {
                readText(file, delimiter, sink);
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Fichier illisible : " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private static void readText(Path file, char delimiter, BiPredicate<Integer, String[]> sink) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        int offset = hasUtf8Bom(bytes) ? 3 : 0;
        Charset charset = isValidUtf8(bytes, offset) ? StandardCharsets.UTF_8 : Charset.forName("windows-1252");
        try (InputStream in = new java.io.ByteArrayInputStream(bytes, offset, bytes.length - offset);
             CSVReader reader = CsvSupport.open(in, charset, delimiter)) {
            String[] cells;
            int recordNo = 0;
            while ((cells = reader.readNext()) != null) {
                recordNo++;
                String[] clean = new String[cells.length];
                for (int i = 0; i < cells.length; i++) {
                    clean[i] = cells[i] == null ? "" : cells[i].strip();
                }
                if (!sink.test(recordNo, clean)) {
                    return;
                }
            }
        }
    }

    private static void readXlsx(Path file, BiPredicate<Integer, String[]> sink) throws Exception {
        try (InputStream in = Files.newInputStream(file); Workbook workbook = WorkbookFactory.create(in)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw ApiException.badRequest("Le classeur ne contient aucune feuille");
            }
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.CANADA_FRENCH);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            int last = sheet.getLastRowNum();
            for (int r = sheet.getFirstRowNum(); r <= last; r++) {
                Row row = sheet.getRow(r);
                String[] cells = new String[row == null ? 0 : Math.max(row.getLastCellNum(), 0)];
                for (int c = 0; c < cells.length; c++) {
                    Cell cell = row.getCell(c);
                    String value;
                    try {
                        value = cell == null ? "" : formatter.formatCellValue(cell, evaluator);
                    } catch (RuntimeException e) {
                        value = ""; // unsupported formula
                    }
                    cells[c] = value.strip();
                }
                if (!sink.test(r + 1, cells)) {
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    static boolean hasUtf8Bom(byte[] b) {
        return b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF;
    }

    static boolean isValidUtf8(byte[] bytes, int offset) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /**
     * Delimiter of a text file: for each candidate, count its occurrences outside quotes on the
     * first non-empty lines; the winner is the delimiter that appears the same (non-zero) number of
     * times on the most lines (so a decimal comma in a semicolon file does not win), then the one
     * with the most occurrences. Comma when nothing is found (single column).
     */
    static char sniffDelimiter(byte[] bytes) {
        int offset = hasUtf8Bom(bytes) ? 3 : 0;
        Charset charset = isValidUtf8(bytes, offset) ? StandardCharsets.UTF_8 : Charset.forName("windows-1252");
        String text = new String(bytes, offset, bytes.length - offset, charset);
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\r\n|\n|\r", -1)) {
            if (!line.isBlank()) {
                lines.add(line);
                if (lines.size() >= SNIFF_LINES) break;
            }
        }
        char best = ',';
        long bestScore = 0;
        for (char candidate : DELIMITERS) {
            int[] counts = new int[lines.size()];
            for (int i = 0; i < counts.length; i++) {
                counts[i] = countOutsideQuotes(lines.get(i), candidate);
            }
            long score = 0;
            for (int modal : new LinkedHashSet<>(Arrays.stream(counts).boxed().toList()).stream().mapToInt(Integer::intValue).toArray()) {
                if (modal == 0) continue;
                long same = Arrays.stream(counts).filter(c -> c == modal).count();
                score = Math.max(score, same * 1000L + modal);
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static int countOutsideQuotes(String line, char delimiter) {
        boolean quoted = false;
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == delimiter && !quoted) {
                count++;
            }
        }
        return count;
    }

    static boolean isBlank(String[] cells) {
        for (String c : cells) {
            if (c != null && !c.isBlank()) return false;
        }
        return true;
    }

    /** Non-blank, unique header names ("Colonne 3" for an empty header, "Nom (2)" for a repeated one). */
    static List<String> headerNames(String[] cells) {
        int last = cells.length;
        while (last > 0 && (cells[last - 1] == null || cells[last - 1].isBlank())) {
            last--;
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < last; i++) {
            String name = cells[i] == null ? "" : cells[i].strip();
            if (name.isEmpty()) {
                name = "Colonne " + (i + 1);
            }
            String unique = name;
            for (int n = 2; !seen.add(unique.toLowerCase(Locale.ROOT)); n++) {
                unique = name + " (" + n + ")";
            }
            names.add(unique);
        }
        return names;
    }

    static List<String> pad(String[] cells, int width) {
        List<String> row = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            row.add(i < cells.length && cells[i] != null ? cells[i] : "");
        }
        return row;
    }
}
