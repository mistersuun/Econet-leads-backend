package com.econet.leads.service;

import com.econet.leads.exception.ApiException;
import com.econet.leads.model.Business;
import com.econet.leads.model.Quote;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/** One-page PDF of a quote (OpenPDF, standard Helvetica: Latin-1 text only). */
@Service
public class QuotePdfService {

    static final Color BRAND = new Color(0x3f, 0x6b, 0x5a);
    private static final Color MUTED = new Color(0x5f, 0x6b, 0x66);
    private static final Color RULE = new Color(0xd9, 0xe1, 0xdd);

    private final String companyName;

    public QuotePdfService(@Value("${app.company.name:EcoNet Propreté}") String companyName) {
        this.companyName = companyName;
    }

    /** The quote's business must be loaded (call inside a transaction or after a join fetch). */
    public byte[] render(Quote quote) {
        boolean en = "en".equals(quote.getLanguage());
        Locale locale = en ? Locale.CANADA : Locale.CANADA_FRENCH;
        DateTimeFormatter dates = DateTimeFormatter.ofPattern(en ? "MMMM d, yyyy" : "d MMMM yyyy", locale);
        Business b = quote.getBusiness();

        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, BRAND);
        Font h = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.BLACK);
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.BLACK);
        Font muted = FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);
        Font total = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, BRAND);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.LETTER, 54, 54, 54, 54);
        try {
            PdfWriter.getInstance(doc, out);
            doc.addTitle((en ? "Quote " : "Soumission ") + quote.getNumber());
            doc.addAuthor(companyName);
            doc.open();

            doc.add(new Paragraph(companyName, title));
            Paragraph meta = new Paragraph((en ? "Quote " : "Soumission ") + quote.getNumber(), h);
            meta.setSpacingBefore(4);
            doc.add(meta);
            String issued = quote.getCreatedAt() != null ? dates.format(quote.getCreatedAt()) : "";
            doc.add(new Paragraph((en ? "Issued " : "Émise le ") + issued + "  ·  "
                    + (en ? "Valid until " : "Valide jusqu'au ") + dates.format(quote.getValidUntil()), muted));

            Paragraph to = new Paragraph(en ? "Prepared for" : "Préparée pour", h);
            to.setSpacingBefore(22);
            doc.add(to);
            doc.add(new Paragraph(nz(b.getBusinessName()), body));
            String address = b.getFullAddress();
            if (address != null && !address.isBlank()) {
                doc.add(new Paragraph(address, body));
            }
            String contact = QualificationService.text(b.getQualification(), "decisionMaker");
            if (contact != null && !contact.isBlank()) {
                doc.add(new Paragraph(contact, body));
            }

            Map<String, Object> input = quote.getInput();
            Paragraph spec = new Paragraph(specLine(input, en), muted);
            spec.setSpacingBefore(14);
            doc.add(spec);

            PdfPTable table = new PdfPTable(new float[]{4f, 1.4f});
            table.setWidthPercentage(100);
            table.setSpacingBefore(14);
            table.addCell(cell(en ? "Description" : "Description", h, Element.ALIGN_LEFT, true));
            table.addCell(cell(en ? "Amount" : "Montant", h, Element.ALIGN_RIGHT, true));
            for (Map<String, Object> line : quote.getLines()) {
                table.addCell(cell(String.valueOf(line.get("label")), body, Element.ALIGN_LEFT, false));
                double amount = ((Number) line.get("amount")).doubleValue();
                table.addCell(cell((amount < 0 ? "-" : "") + money(Math.abs(amount), locale), body, Element.ALIGN_RIGHT, false));
            }
            table.addCell(cell(en ? "Subtotal" : "Sous-total", bold, Element.ALIGN_RIGHT, false));
            table.addCell(cell(money(quote.getSubtotal().doubleValue(), locale), bold, Element.ALIGN_RIGHT, false));
            table.addCell(cell(en ? "Taxes (GST + QST 14.975%)" : "Taxes (TPS + TVQ 14,975 %)", body, Element.ALIGN_RIGHT, false));
            table.addCell(cell(money(quote.getTax().doubleValue(), locale), body, Element.ALIGN_RIGHT, false));
            table.addCell(cell("Total", total, Element.ALIGN_RIGHT, false));
            table.addCell(cell(money(quote.getTotal().doubleValue(), locale), total, Element.ALIGN_RIGHT, false));
            doc.add(table);

            Paragraph terms = new Paragraph(en
                    ? "This price covers labour, supplies and equipment. It is confirmed after a short on-site visit, with no obligation. "
                    + "The quote is valid until " + dates.format(quote.getValidUntil()) + "."
                    : "Ce prix comprend la main-d'œuvre, les produits et l'équipement. Il est confirmé après une courte visite des lieux, "
                    + "sans engagement. La soumission est valide jusqu'au " + dates.format(quote.getValidUntil()) + ".", muted);
            terms.setSpacingBefore(20);
            doc.add(terms);
            Paragraph accept = new Paragraph(en
                    ? "To accept this quote, use the link in the email we sent you, or reply to that email."
                    : "Pour accepter cette soumission, utilisez le lien du courriel que nous vous avons envoyé, ou répondez à ce courriel.", muted);
            accept.setSpacingBefore(6);
            doc.add(accept);
        } catch (DocumentException e) {
            throw ApiException.badGateway("PDF generation failed");
        } finally {
            if (doc.isOpen()) {
                doc.close();
            }
        }
        return out.toByteArray();
    }

    private static String specLine(Map<String, Object> input, boolean en) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        append(sb, QuoteLabels.service(str(input.get("service")), en));
        append(sb, QuoteLabels.area(str(input.get("area")), en));
        append(sb, QuoteLabels.propertyType(str(input.get("propertyType")), en));
        append(sb, (en ? "Frequency: " : "Fréquence : ") + QuoteLabels.frequency(str(input.get("frequency")), en));
        return sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (part == null || part.isBlank()) return;
        if (sb.length() > 0) sb.append("  ·  ");
        sb.append(part);
    }

    private static PdfPCell cell(String text, Font font, int align, boolean header) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setHorizontalAlignment(align);
        c.setPadding(6);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(RULE);
        c.setBorderWidth(header ? 1.2f : 0.5f);
        return c;
    }

    static String money(double amount, Locale locale) {
        return EmailService.money(amount, locale);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
