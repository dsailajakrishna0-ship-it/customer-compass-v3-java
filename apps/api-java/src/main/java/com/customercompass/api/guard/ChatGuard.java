package com.customercompass.api.guard;

import com.customercompass.api.crm.CrmFacts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What every route does when it has an answer: scan the answer, merge that into the report, write
 * each detection to the audit log, and return the safe answer with the {@code guardrail} field the
 * web app shows as a warning tag.
 */
@Component
public class ChatGuard {

  private static final Logger log = LoggerFactory.getLogger(ChatGuard.class);

  private final SecurityAudit audit;
  private final CrmFacts facts;

  public ChatGuard(SecurityAudit audit, CrmFacts facts) {
    this.audit = audit;
    this.facts = facts;
  }

  /** The answer after the output scan, and the guardrail field. */
  public record Outcome(String answer, Map<String, Object> guardrail) {}

  public Outcome finalizeAnswer(
      String endpoint, PromptGuard.Report report, String answer, String scopedCompany) {
    List<String> names = List.of();
    if (scopedCompany != null) {
      try {
        names = facts.companies().stream().map(c -> c.name()).toList();
      } catch (RuntimeException e) {
        // the output scan still runs without the name list
      }
    }
    PromptGuard.Finding output = PromptGuard.scanOutput(answer, scopedCompany, names);
    PromptGuard.Report merged = PromptGuard.add(report, output, "output");
    if (merged.flagged()) {
      log.warn("prompt_guard_flagged {}", merged.reasons());
      audit.record(endpoint, merged.events());
    }
    return new Outcome(output.neutralizedText(), guardrail(merged));
  }

  public Outcome finalizeAnswer(String endpoint, PromptGuard.Report report, String answer) {
    return finalizeAnswer(endpoint, report, answer, null);
  }

  static Map<String, Object> guardrail(PromptGuard.Report report) {
    var map = new LinkedHashMap<String, Object>();
    map.put("flagged", report.flagged());
    map.put("reasons", report.reasons());
    return map;
  }
}
