package com.customercompass.api.mcp;

import com.customercompass.api.crm.CompanySummary;
import com.customercompass.api.crm.CompanySummary.CompanyRef;
import com.customercompass.api.crm.CompanySummary.DealFact;
import com.customercompass.api.crm.CrmFacts;
import com.customercompass.api.rag.RetrievedChunk;
import com.customercompass.api.rag.Retriever;
import com.customercompass.api.rag.VectorIndex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The four MCP tools, served by this application at /mcp. Names, arguments and answers follow
 * contracts/mcp-tools.json, which the Python edition's tool servers follow too, so any client of
 * one works with the other. Every call is written to the audit log.
 *
 * <p>The parameter names are snake_case on purpose: Spring AI takes a tool's argument names from
 * the Java parameter names, and the contract says {@code company_name}, not {@code companyName}.
 *
 * <p>Retrieved document text is untrusted data. These tools return it as it is; the chat loop that
 * calls them (J4.3) wraps it before the model sees it.
 */
@Component
@SuppressWarnings("checkstyle:ParameterName")
public class CrmTools {

  private final CrmFacts facts;
  private final Retriever retriever;
  private final ToolLog log;

  public CrmTools(CrmFacts facts, Retriever retriever, ToolLog log) {
    this.facts = facts;
    this.retriever = retriever;
    this.log = log;
  }

  @McpTool(
      name = "get_company_summary",
      description =
          "Get verified CRM facts for a company: industry, relationship status, open deals, "
              + "and won/lost deal counts.")
  public Map<String, Object> getCompanySummary(
      @McpToolParam(description = "The company's name, as the user wrote it.", required = true)
          String company_name) {
    CompanyRef company = resolve(company_name);
    if (company == null) {
      return notFound("get_company_summary", Map.of("company_name", company_name), company_name);
    }
    CompanySummary summary = facts.summary(company.id()).orElseThrow();
    var view = new LinkedHashMap<String, Object>();
    view.put("id", summary.id());
    view.put("name", summary.name());
    view.put("industry", summary.industry());
    view.put("relationshipStatus", summary.relationshipStatus());
    view.put("openDeals", deals(summary));
    view.put("wonDealsCount", summary.wonDealsCount());
    view.put("lostDealsCount", summary.lostDealsCount());
    log.audit(
        "get_company_summary",
        Map.of("company_name", company_name),
        "executed",
        "Returned summary for " + summary.name(),
        summary.id());
    return Map.of("status", "executed", "company", view);
  }

  @McpTool(
      name = "get_open_deals",
      description =
          "Get only the open deals for a company (name, amount, status, expected close date).")
  public Map<String, Object> getOpenDeals(
      @McpToolParam(description = "The company's name, as the user wrote it.", required = true)
          String company_name) {
    CompanyRef company = resolve(company_name);
    if (company == null) {
      return notFound("get_open_deals", Map.of("company_name", company_name), company_name);
    }
    CompanySummary summary = facts.summary(company.id()).orElseThrow();
    log.audit(
        "get_open_deals",
        Map.of("company_name", company_name),
        "executed",
        "Returned " + summary.openDeals().size() + " open deal(s) for " + summary.name(),
        summary.id());
    return Map.of("status", "executed", "companyName", summary.name(), "openDeals", deals(summary));
  }

  @McpTool(
      name = "search_documents",
      description =
          "Search CRM interaction documents (emails, calls, support tickets, meeting notes) "
              + "semantically. Optionally scope to one company.")
  public Map<String, Object> searchDocuments(
      @McpToolParam(description = "What to look for in the interaction documents.", required = true)
          String query,
      @McpToolParam(description = "Only search this company's documents.", required = false)
          String company_name) {
    CompanyRef company = company_name == null ? null : resolve(company_name);
    List<RetrievedChunk> chunks;
    try {
      chunks = retriever.retrieve(query, company == null ? null : company.id());
    } catch (RuntimeException e) {
      if (VectorIndex.isUnavailable(e)) {
        throw new IllegalStateException(VectorIndex.UNAVAILABLE_MESSAGE, e);
      }
      throw e;
    }
    var results = new ArrayList<Map<String, Object>>();
    for (RetrievedChunk chunk : chunks) {
      var result = new LinkedHashMap<String, Object>();
      result.put("title", chunk.documentTitle());
      result.put("companyName", chunk.companyName());
      result.put("docType", chunk.docType());
      result.put("similarity", chunk.similarity());
      result.put("content", chunk.content());
      results.add(result);
    }
    var arguments = new LinkedHashMap<String, Object>();
    arguments.put("query", query);
    arguments.put("company_name", company_name);
    log.audit(
        "search_documents",
        arguments,
        "executed",
        "Found " + chunks.size() + " document chunk(s)",
        company == null ? null : company.id());
    return Map.of("status", "executed", "results", results);
  }

  @McpTool(
      name = "create_followup_task",
      description =
          "Propose (confirm=false, the default) or create (confirm=true) a follow-up task for a "
              + "company. Only pass confirm=true after the user has explicitly confirmed the "
              + "proposed task in their latest message.")
  public Map<String, Object> createFollowupTask(
      @McpToolParam(description = "The company the follow-up is for.", required = true)
          String company_name,
      @McpToolParam(description = "What the follow-up task is.", required = true) String note,
      @McpToolParam(description = "true only after the user confirmed.", required = false)
          Boolean confirm) {
    boolean confirmed = Boolean.TRUE.equals(confirm);
    var asked = new LinkedHashMap<String, Object>();
    asked.put("company_name", company_name);
    asked.put("note", note);
    CompanyRef company = resolve(company_name);
    if (company == null) {
      return notFound("create_followup_task", asked, company_name);
    }
    asked.put("confirm", confirmed);
    if (!confirmed) {
      // A write is only ever proposed here. The chat route decides when the user has confirmed.
      log.audit(
          "create_followup_task",
          asked,
          "proposed",
          "Proposed task for " + company.name() + ": " + note,
          company.id());
      return Map.of(
          "status", "proposed",
          "message",
              "Task not yet created -- ask the user to confirm before calling this tool again.",
          "proposedTask", Map.of("companyName", company.name(), "note", note));
    }
    ToolLog.Task task = log.createTask(company.id(), note);
    log.audit(
        "create_followup_task",
        asked,
        "confirmed",
        "Created task #" + task.id() + " for " + company.name(),
        company.id());
    return Map.of(
        "status",
        "confirmed",
        "task",
        Map.of(
            "id", task.id(),
            "companyName", company.name(),
            "note", note,
            "status", task.status(),
            "createdAt", task.createdAt()));
  }

  private CompanyRef resolve(String companyName) {
    return CompanyRef.findMentioned(companyName, facts.companies());
  }

  /** An open deal as the tools show it: no database id (the contract does not list one). */
  private static List<Map<String, Object>> deals(CompanySummary summary) {
    var deals = new ArrayList<Map<String, Object>>();
    for (DealFact deal : summary.openDeals()) {
      var view = new LinkedHashMap<String, Object>();
      view.put("name", deal.name());
      view.put("amount", deal.amount());
      view.put("status", deal.status());
      view.put("expectedCloseDate", deal.expectedCloseDate());
      deals.add(view);
    }
    return deals;
  }

  private Map<String, Object> notFound(
      String tool, Map<String, Object> arguments, String companyName) {
    String message = "No company matching \"" + companyName + "\" was found.";
    log.audit(tool, arguments, "error", message, null);
    return Map.of("status", "not_found", "message", message);
  }
}
