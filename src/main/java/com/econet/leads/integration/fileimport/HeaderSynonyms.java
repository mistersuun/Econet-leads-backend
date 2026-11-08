package com.econet.leads.integration.fileimport;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Suggests which column holds which lead field from the header text.
 *
 * Headers are normalised (lower case, accents removed, every run of non-alphanumeric characters
 * becomes one space) and compared with the synonym lists below, in two passes: first an exact match
 * of the whole header, then a match of a synonym as a whole-word phrase inside the header ("Nom de
 * l'entreprise" contains "entreprise", "Téléphone 2" contains "telephone"; the longest synonym wins).
 * A column is suggested for at most one field, fields are resolved in the order of {@link #FIELDS},
 * and a field without any matching column maps to null.
 */
public final class HeaderSynonyms {

    /** Target fields in resolution order. */
    public static final List<String> FIELDS = List.of("businessName", "phone", "email", "website", "addressStreet",
            "addressCity", "postalCode", "businessType", "notes", "decisionMaker");

    public static final Map<String, List<String>> SYNONYMS = new LinkedHashMap<>();

    static {
        SYNONYMS.put("businessName", List.of("nom", "name", "entreprise", "company", "nom entreprise", "nom de l entreprise",
                "nom commercial", "raison sociale", "compagnie", "societe", "company name", "business name", "business",
                "organisation", "organization", "etablissement", "client"));
        SYNONYMS.put("phone", List.of("telephone", "tel", "phone", "phone number", "numero de telephone", "no telephone",
                "tel principal", "cell", "cellulaire", "mobile", "tel bureau"));
        SYNONYMS.put("email", List.of("courriel", "email", "e mail", "mail", "adresse courriel", "adresse email",
                "adresse e mail", "courrier electronique"));
        SYNONYMS.put("website", List.of("site", "web", "website", "site web", "siteweb", "site internet", "url", "www"));
        SYNONYMS.put("addressStreet", List.of("adresse", "address", "rue", "street", "adresse civique", "street address",
                "adresse postale", "adresse 1"));
        SYNONYMS.put("addressCity", List.of("ville", "city", "municipalite", "localite", "town"));
        SYNONYMS.put("postalCode", List.of("code postal", "postal", "postal code", "codepostal", "zip", "zip code", "cp"));
        SYNONYMS.put("businessType", List.of("type", "categorie", "category", "secteur", "industrie", "industry",
                "type d entreprise", "type de commerce", "domaine"));
        SYNONYMS.put("notes", List.of("notes", "note", "commentaire", "commentaires", "remarque", "remarques", "comment",
                "comments", "description"));
        SYNONYMS.put("decisionMaker", List.of("contact", "responsable", "decideur", "personne ressource", "nom du contact",
                "contact name", "decision maker", "personne contact", "proprietaire", "owner", "gestionnaire"));
    }

    private HeaderSynonyms() {
    }

    /** field -> column name (as it appears in {@code columns}) or null; every field is present. */
    public static Map<String, String> suggest(List<String> columns) {
        Map<String, String> result = new LinkedHashMap<>();
        FIELDS.forEach(f -> result.put(f, null));
        List<String> normalized = new ArrayList<>();
        for (String c : columns) {
            normalized.add(normalize(c));
        }
        Set<Integer> used = new java.util.HashSet<>();

        // pass 1: whole header equals a synonym
        for (String field : FIELDS) {
            outer:
            for (String synonym : SYNONYMS.get(field)) {
                for (int i = 0; i < columns.size(); i++) {
                    if (!used.contains(i) && normalized.get(i).equals(synonym)) {
                        result.put(field, columns.get(i));
                        used.add(i);
                        break outer;
                    }
                }
            }
        }
        // pass 2: a synonym appears as whole words in the header; the longest synonym wins
        for (String field : FIELDS) {
            if (result.get(field) != null) continue;
            int bestColumn = -1;
            int bestLength = 0;
            for (int i = 0; i < columns.size(); i++) {
                if (used.contains(i)) continue;
                String padded = " " + normalized.get(i) + " ";
                for (String synonym : SYNONYMS.get(field)) {
                    if (synonym.length() > bestLength && padded.contains(" " + synonym + " ")) {
                        bestLength = synonym.length();
                        bestColumn = i;
                    }
                }
            }
            if (bestColumn >= 0) {
                result.put(field, columns.get(bestColumn));
                used.add(bestColumn);
            }
        }
        return result;
    }

    static String normalize(String header) {
        String s = Normalizer.normalize(header == null ? "" : header, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return s;
    }
}
