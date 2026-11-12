package com.customercompass.api.chat;

import com.customercompass.api.crm.CompanySummary.CompanyRef;
import com.customercompass.api.crm.CrmFacts;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Deterministic, explainable routing (the Python edition's router.py). Deal and pipeline words send
 * a question to the CRM facts, "said", "email" and "call" words send it to document search, and a
 * question that matches neither goes to document search. A plain keyword match, not a model call,
 * so it is fast, free and easy to follow. The company a question names is found the same way: by a
 * significant word (more than three letters) of a known company name.
 */
@Service
public class QueryRouter {

  static final List<String> STRUCTURED_KEYWORDS =
      List.of(
          "deal",
          "deals",
          "pipeline",
          "amount",
          "value",
          "status of",
          "close date",
          "revenue",
          "won",
          "lost",
          "relationship status");

  static final List<String> RAG_KEYWORDS =
      List.of(
          "said",
          "say",
          "says",
          "mention",
          "discuss",
          "told",
          "email",
          "call",
          "note",
          "conversation",
          "feedback",
          "asked",
          "requested",
          "complain");

  private final CrmFacts facts;

  public QueryRouter(CrmFacts facts) {
    this.facts = facts;
  }

  public QueryRoute classify(String question) {
    String lower = question.toLowerCase(Locale.ROOT);
    CompanyRef company = findMentionedCompany(question, facts.companies());
    boolean wantsStructured = matchesAny(lower, STRUCTURED_KEYWORDS);
    boolean wantsRag = matchesAny(lower, RAG_KEYWORDS) || !wantsStructured;
    return new QueryRoute(wantsStructured, wantsRag, company);
  }

  /** The first company whose name is clearly referenced in the text, or null. */
  static CompanyRef findMentionedCompany(String text, List<CompanyRef> companies) {
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

  private static boolean matchesAny(String text, List<String> keywords) {
    return keywords.stream().anyMatch(text::contains);
  }
}
