package com.customercompass.api.crm;

import com.customercompass.api.crm.CompanySummary.CompanyRef;
import com.customercompass.api.crm.CompanySummary.DealFact;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Structured CRM facts: plain SQL against the relational tables, no embeddings and no model. What
 * comes from here is already correct and needs no citation, unlike a claim from a document chunk
 * (the Python edition's crm/queries.py).
 */
@Service
public class CrmFacts {

  private final JdbcClient jdbc;

  public CrmFacts(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** All companies, for finding which one a question names. */
  public List<CompanyRef> companies() {
    return jdbc.sql("SELECT id, name FROM companies ORDER BY name")
        .query((rs, rowNum) -> new CompanyRef(rs.getInt("id"), rs.getString("name")))
        .list();
  }

  /** One logged contact with a company: an email, call, meeting or support ticket. */
  public record Interaction(
      int id, String interactionType, String subject, String occurredAt, String summary) {}

  /**
   * The most recent interactions with a company, newest first (the history evidence of the agent).
   */
  public List<Interaction> recentInteractions(int companyId, int limit) {
    return jdbc.sql(
            """
            SELECT id, interaction_type, subject, occurred_at::text AS occurred_at, summary
            FROM interactions WHERE company_id = :id
            ORDER BY occurred_at DESC LIMIT :limit""")
        .param("id", companyId)
        .param("limit", limit)
        .query(
            (rs, rowNum) ->
                new Interaction(
                    rs.getInt("id"),
                    rs.getString("interaction_type"),
                    rs.getString("subject"),
                    rs.getString("occurred_at"),
                    rs.getString("summary")))
        .list();
  }

  public Optional<CompanySummary> summary(int companyId) {
    return jdbc.sql("SELECT id, name, industry, relationship_status FROM companies WHERE id = :id")
        .param("id", companyId)
        .query(
            (rs, rowNum) ->
                new Object[] {
                  rs.getInt("id"),
                  rs.getString("name"),
                  rs.getString("industry"),
                  rs.getString("relationship_status")
                })
        .optional()
        .map(
            company -> {
              Map<String, Integer> counts = new HashMap<>();
              jdbc.sql(
                      "SELECT status, COUNT(*)::int AS count FROM deals"
                          + " WHERE company_id = :id GROUP BY status")
                  .param("id", companyId)
                  .query((rs, rowNum) -> counts.put(rs.getString("status"), rs.getInt("count")))
                  .list();
              return new CompanySummary(
                  (Integer) company[0],
                  (String) company[1],
                  (String) company[2],
                  (String) company[3],
                  openDeals(companyId),
                  counts.getOrDefault("won", 0),
                  counts.getOrDefault("lost", 0));
            });
  }

  private List<DealFact> openDeals(int companyId) {
    return jdbc.sql(
            """
            SELECT id, name, amount::text AS amount, status,
                   expected_close_date::text AS expected_close_date
            FROM deals WHERE company_id = :id AND status = 'open'
            ORDER BY expected_close_date NULLS LAST""")
        .param("id", companyId)
        .query(
            (rs, rowNum) ->
                new DealFact(
                    rs.getInt("id"),
                    rs.getString("name"),
                    rs.getString("amount"),
                    rs.getString("status"),
                    rs.getString("expected_close_date")))
        .list();
  }
}
