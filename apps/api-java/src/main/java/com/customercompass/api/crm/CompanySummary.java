package com.customercompass.api.crm;

import java.util.List;

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
  public record CompanyRef(int id, String name) {}
}
