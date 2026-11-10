package com.customercompass.api.crm;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The JSON shapes of the CRM routes. Field names are snake_case on purpose: they are the Python
 * edition's names, which the web app's types (apps/web/src/types) already use.
 */
public final class Records {

  private Records() {}

  public record CompanySummary(
      long id,
      String name,
      String industry,
      @JsonProperty("relationship_status") String relationshipStatus,
      @JsonProperty("deal_count") int dealCount,
      @JsonProperty("interaction_count") int interactionCount) {}

  public record Contact(
      long id,
      @JsonProperty("full_name") String fullName,
      @JsonProperty("job_title") String jobTitle,
      String email) {}

  public record Deal(
      long id,
      String name,
      String amount,
      String status,
      @JsonProperty("expected_close_date") String expectedCloseDate) {}

  public record Interaction(
      long id,
      @JsonProperty("interaction_type") String interactionType,
      String subject,
      @JsonProperty("occurred_at") String occurredAt,
      String summary) {}

  public record CompanyDetail(
      long id,
      String name,
      String industry,
      String website,
      @JsonProperty("relationship_status") String relationshipStatus,
      List<Contact> contacts,
      List<Deal> deals,
      List<Interaction> interactions) {}

  public record Task(
      long id,
      @JsonProperty("company_id") long companyId,
      @JsonProperty("company_name") String companyName,
      String note,
      String status,
      @JsonProperty("created_at") String createdAt) {}
}
