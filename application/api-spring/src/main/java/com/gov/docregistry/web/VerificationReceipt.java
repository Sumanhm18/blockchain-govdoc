package com.gov.docregistry.web;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** One-page, text-only PDF. No scripts, remote assets, identity claims or embedded source files. */
public final class VerificationReceipt {
  private VerificationReceipt() {}

  public static byte[] create(String id, JsonNode result) {
    String status = result.path("status").asText();
    if (!Set.of("VALID", "TAMPERED_OR_MISMATCH", "REVOKED", "EXPIRED").contains(status))
      throw RegistryException.unavailable();
    String hash = result.path("providedHash").asText();
    StringBuilder content = new StringBuilder("0.08 0.32 0.28 rg\n");
    line(content, "F2", 26, 54, 774, "GovDoc");
    line(content, "F1", 11, 54, 752, "DOCUMENT REGISTRY / VERIFICATION RECEIPT");
    content.append("0.80 0.86 0.83 RG 54 733 m 541 733 l S\n");
    if (!status.equals("VALID")) content.append("0.65 0.16 0.12 rg\n");
    line(content, "F2", 22, 54, 692, status);
    content.append("0.12 0.18 0.17 rg\n");
    line(content, "F2", 10, 54, 648, "DOCUMENT ID");
    line(content, "F1", id.length() > 40 ? 10 : 12, 54, 628, id);
    line(content, "F2", 10, 54, 588, "CHECK COMPLETED (UTC)");
    line(content, "F1", 12, 54, 568, result.path("checkedAt").asText());
    line(content, "F2", 10, 54, 528, "SUBMITTED FILE SHA-256");
    line(content, "F1", 11, 54, 508, hash.substring(0, Math.min(39, hash.length())));
    if (hash.length() > 39) line(content, "F1", 11, 54, 491, hash.substring(39));
    line(content, "F1", 12, 54, 446, "File digest matches registered digest: " + (result.path("matches").asBoolean() ? "Yes" : "No"));
    line(content, "F1", 12, 54, 423, "Revoked: " + (result.path("revoked").asBoolean() ? "Yes" : "No"));
    line(content, "F1", 12, 54, 400, "Expired: " + (result.path("expired").asBoolean() ? "Yes" : "No"));
    content.append("0.80 0.86 0.83 RG 54 365 m 541 365 l S\n");
    line(content, "F2", 12, 54, 336, "How to use this receipt");
    String[] notes = {
      "This records a file verification result at the time shown above.",
      "The registry rechecked the submitted file before generating this PDF.",
      "A later revocation or expiry can change the result. Reverify when needed.",
      "This PDF is not digitally signed and is not an official certificate.",
      "A matching digest does not prove the issuer's original claims are true.",
      "The source file, account credentials and citizen reference are not included."
    };
    for (int i = 0; i < notes.length; i++) line(content, "F1", 11, 54, 310 - i * 20, notes[i]);
    line(content, "F1", 9, 54, 54, "GovDoc local prototype | Verification snapshot | Page 1 of 1");
    String stream = content.toString();
    List<String> objects = List.of(
      "<< /Type /Catalog /Pages 2 0 R >>",
      "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
      "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>",
      "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
      "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>",
      "<< /Length " + bytes(stream).length + " >>\nstream\n" + stream + "endstream"
    );
    ByteArrayOutputStream pdf = new ByteArrayOutputStream();
    pdf.writeBytes(bytes("%PDF-1.4\n"));
    List<Integer> offsets = new ArrayList<>();
    for (int i = 0; i < objects.size(); i++) {
      offsets.add(pdf.size());
      pdf.writeBytes(bytes((i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n"));
    }
    int xref = pdf.size();
    pdf.writeBytes(bytes("xref\n0 7\n0000000000 65535 f \n"));
    for (int offset : offsets) pdf.writeBytes(bytes(String.format(Locale.ROOT, "%010d 00000 n \n", offset)));
    pdf.writeBytes(bytes("trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n"));
    return pdf.toByteArray();
  }

  private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }

  private static void line(StringBuilder out, String font, int size, int x, int y, String text) {
    String safe = text.replaceAll("[^\\x20-\\x7E]", "?").replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    out.append("BT /").append(font).append(' ').append(size).append(" Tf ").append(x).append(' ').append(y)
      .append(" Td (").append(safe).append(") Tj ET\n");
  }
}
