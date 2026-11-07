package com.customercompass.api.crm;

import com.customercompass.api.crm.Records.CompanyDetail;
import com.customercompass.api.crm.Records.CompanySummary;
import com.customercompass.api.crm.Records.Contact;
import com.customercompass.api.crm.Records.Deal;
import com.customercompass.api.crm.Records.Interaction;
import com.customercompass.api.crm.Records.Task;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL, the same statements as the Python edition's routes (no ORM). */
@Repository
public class CrmRepository {

  private static final DateTimeFormatter WHOLE_SECONDS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");
  private static final DateTimeFormatter MICROS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx");

  private final JdbcClient jdbc;

  public CrmRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<CompanySummary> listCompanies() {
    return jdbc.sql(
            """
            SELECT c.id, c.name, c.industry, c.relationship_status,
                   COUNT(DISTINCT d.id)::int AS deal_count,
                   COUNT(DISTINCT i.id)::int AS interaction_count
            FROM companies c
            LEFT JOIN deals d ON d.company_id = c.id
            LEFT JOIN interactions i ON i.company_id = c.id
            GROUP BY c.id
            ORDER BY c.name
            """)
        .query(
            (rs, n) ->
                new CompanySummary(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getString("industry"),
                    rs.getString("relationship_status"),
                    rs.getInt("deal_count"),
                    rs.getInt("interaction_count")))
        .list();
  }

  public Optional<CompanyDetail> findCompany(long id) {
    var company =
        jdbc.sql(
                "SELECT id, name, industry, website, relationship_status FROM companies WHERE id = :id")
            .param("id", id)
            .query(
                (rs, n) ->
                    new String[] {
                      rs.getString("name"),
                      rs.getString("industry"),
                      rs.getString("website"),
                      rs.getString("relationship_status")
                    })
            .optional();
    if (company.isEmpty()) {
      return Optional.empty();
    }
    String[] c = company.get();

    List<Contact> contacts =
        jdbc.sql(
                "SELECT id, full_name, job_title, email FROM contacts WHERE company_id = :id ORDER BY full_name")
            .param("id", id)
            .query(
                (rs, n) ->
                    new Contact(
                        rs.getLong("id"),
                        rs.getString("full_name"),
                        rs.getString("job_title"),
                        rs.getString("email")))
            .list();
    List<Deal> deals =
        jdbc.sql(
                """
                SELECT id, name, amount, status, expected_close_date::text AS expected_close_date
                FROM deals WHERE company_id = :id ORDER BY expected_close_date DESC
                """)
            .param("id", id)
            .query(
                (rs, n) ->
                    new Deal(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getBigDecimal("amount").toPlainString(),
                        rs.getString("status"),
                        rs.getString("expected_close_date")))
            .list();
    List<Interaction> interactions =
        jdbc.sql(
                """
                SELECT id, interaction_type, subject, occurred_at, summary
                FROM interactions WHERE company_id = :id ORDER BY occurred_at DESC
                """)
            .param("id", id)
            .query(
                (rs, n) ->
                    new Interaction(
                        rs.getLong("id"),
                        rs.getString("interaction_type"),
                        rs.getString("subject"),
                        isoUtc(rs),
                        rs.getString("summary")))
            .list();
    return Optional.of(
        new CompanyDetail(id, c[0], c[1], c[2], c[3], contacts, deals, interactions));
  }

  public List<Task> listTasks(Long companyId) {
    String sql =
        """
        SELECT t.id, t.company_id, c.name AS company_name, t.note, t.status,
               t.created_at::text AS created_at
        FROM tasks t JOIN companies c ON c.id = t.company_id
        """
            + (companyId != null ? "WHERE t.company_id = :companyId " : "")
            + "ORDER BY t.created_at DESC";
    var statement = jdbc.sql(sql);
    if (companyId != null) {
      statement = statement.param("companyId", companyId);
    }
    return statement
        .query(
            (rs, n) ->
                new Task(
                    rs.getLong("id"),
                    rs.getLong("company_id"),
                    rs.getString("company_name"),
                    rs.getString("note"),
                    rs.getString("status"),
                    rs.getString("created_at")))
        .list();
  }

  /**
   * Python's isoformat for a UTC timestamp: 2026-08-28T10:00:00+00:00 (microseconds only if set).
   */
  private static String isoUtc(ResultSet rs) throws SQLException {
    OffsetDateTime at = rs.getObject("occurred_at", OffsetDateTime.class);
    if (at == null) {
      return null;
    }
    OffsetDateTime utc = at.withOffsetSameInstant(ZoneOffset.UTC);
    return (utc.getNano() == 0 ? WHOLE_SECONDS : MICROS).format(utc);
  }
}
