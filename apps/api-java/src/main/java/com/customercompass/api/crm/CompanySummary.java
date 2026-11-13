package com.customercompass.api.crm;

import java.util.List;
import java.util.Locale;

/** Structured facts about one company: status, open deals and deal outcome counts. */
public record CompanySummary(
    int id,
    String name,
    String industry,
    String relationshipStatus,
    List<DealFact> openDeals,
    int wonDealsCount,
    int lostDealsCount) {

  /** An open deal. The amount is Postgres's own text for the number (for example 48000.00). */
  public record DealFact(
      int id, String name, String amount, String status, String expectedCloseDate) {}

  /** A company's id and name, used to find which company a question is about. */
  public record CompanyRef(int id, String name) {

    /**
     * The first company whose name is clearly referenced in free text: any word of the name longer
     * than three characters that appears in the text, ignoring case (the Python edition's
     * find_mentioned_company). One rule for the question router and for the tool arguments, so
     * "Acme" finds "Acme Fabrication" in both.
     */
    public static CompanyRef findMentioned(String text, List<CompanyRef> companies) {
      String lower = text.toLowerCase(Locale.ROOT);
      for (CompanyRef company : companies) {
        for (String word : company.name().split("\\s+")) {
          if (word.length() > 3 && lower.contains(word.toLowerCase(Locale.ROOT))) {
            return company;
          }
        }
      }
      return null;
    }
  }
}
