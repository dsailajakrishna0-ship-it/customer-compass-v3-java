package com.customercompass.comparisons.agentgraph.handwritten;

import com.customercompass.comparisons.common.Agent;
import com.customercompass.comparisons.common.CrmTools;
import com.customercompass.comparisons.common.Recommender;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A simple agent with an approval pause, no AI framework: four steps written as plain methods, and
 * the pause is just a run stored in a map with the status {@code awaiting_approval}. {@code start}
 * runs {@code gather} (the CRM tool) and {@code recommend} (one model call), stores the run and
 * returns; {@code resume} finds the stored run and runs {@code act} (a dry run: nothing is written)
 * or skips it. The run store keeps only the most recent {@value #MAX_RUNS} runs, so a stream of
 * starts cannot fill memory; an evicted run is simply unknown when someone resumes it.
 *
 * <p>Not durable: the runs live in this process, so a restart forgets them (the application's own
 * agent keeps its pause in the database, J5.2).
 */
public final class HandWrittenAgent implements Agent {

  static final int MAX_RUNS = 100;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** One run: what it needs to continue after the pause, and where it is. */
  private static final class Run {
    final String id;
    final String companyName;
    final Recommender.Recommendation recommendation;
    String status = "awaiting_approval";
    String message = "Waiting for approval of the recommended action.";

    Run(String id, String companyName, Recommender.Recommendation recommendation) {
      this.id = id;
      this.companyName = companyName;
      this.recommendation = recommendation;
    }
  }

  private final CrmTools tools;
  private final Recommender recommender;
  private final Map<String, Run> runs =
      java.util.Collections.synchronizedMap(
          new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Run> eldest) {
              return size() > MAX_RUNS;
            }
          });

  public HandWrittenAgent(CrmTools tools, Recommender recommender) {
    this.tools = tools;
    this.recommender = recommender;
  }

  @Override
  public String approach() {
    return "handWritten";
  }

  @Override
  public Outcome start(String company, String model) {
    // step 1, gather: the CRM facts (plain code, no model)
    String facts;
    try {
      facts = tools.call("get_company_summary", Map.of("company_name", company));
    } catch (RuntimeException e) {
      throw new UnavailableException("The CRM could not be reached.", e);
    }
    JsonNode parsed = parse(facts);
    if (!"executed".equals(parsed.path("status").asString(""))) {
      throw new CompanyNotFoundException("No company matching \"" + company + "\" was found.");
    }
    String companyName = parsed.path("company").path("name").asString(company);

    // step 2, recommend: the one model call
    Recommender.Recommendation recommendation = recommender.recommend(facts, model);

    // step 3, approval: nothing to run; the run is stored and this call returns. resume() continues it.
    var run = new Run(UUID.randomUUID().toString(), companyName, recommendation);
    runs.put(run.id, run);
    return outcome(run);
  }

  @Override
  public Outcome resume(String runId, boolean approve) {
    Run run = runs.get(runId);
    if (run == null) {
      throw new UnknownRunException("No run with that id. It was never started, or it is no longer kept.");
    }
    synchronized (run) {
      if (!run.status.equals("awaiting_approval")) {
        throw new NotAwaitingApprovalException("This run is not waiting for approval: it is already " + run.status + ".");
      }
      if (approve) {
        // step 4, act: a dry run, nothing is written anywhere
        run.status = "completed";
        run.message =
            "Approved. Dry run: this would create a follow-up task for "
                + run.companyName
                + ": "
                + run.recommendation.action()
                + " Nothing was written.";
      } else {
        run.status = "declined";
        run.message = "Declined. Nothing was done.";
      }
      return outcome(run);
    }
  }

  private static Outcome outcome(Run run) {
    String approval;
    String act;
    switch (run.status) {
      case "completed" -> {
        approval = "done";
        act = "done";
      }
      case "declined" -> {
        approval = "declined";
        act = "skipped";
      }
      default -> {
        approval = "waiting";
        act = "pending";
      }
    }
    return new Outcome(
        run.id,
        run.status,
        List.of(
            new Step("gather", "done"),
            new Step("recommend", "done"),
            new Step("approval", approval),
            new Step("act", act)),
        run.recommendation,
        run.message);
  }

  private static JsonNode parse(String json) {
    try {
      return JSON.readTree(json);
    } catch (JacksonException e) {
      throw new UnavailableException("The CRM answered with something that is not JSON.");
    }
  }
}
