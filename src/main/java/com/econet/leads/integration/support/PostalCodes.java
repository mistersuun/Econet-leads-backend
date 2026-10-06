package com.econet.leads.integration.support;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Canadian postal code detection ("H2X 1Y4", "h2x1y4"). */
public final class PostalCodes {

    private static final Pattern POSTAL = Pattern.compile("\\b([A-Za-z]\\d[A-Za-z])\\s?(\\d[A-Za-z]\\d)\\b");

    private PostalCodes() {
    }

    /** First postal code in the text as "H2X 1Y4", or null. */
    public static String find(String text) {
        if (text == null) return null;
        Matcher m = POSTAL.matcher(text);
        return m.find() ? (m.group(1) + " " + m.group(2)).toUpperCase(Locale.ROOT) : null;
    }

    public static String strip(String text) {
        return text == null ? null : POSTAL.matcher(text).replaceAll(" ").trim();
    }
}
