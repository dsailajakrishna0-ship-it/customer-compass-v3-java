package com.customercompass.api.crm;

import com.customercompass.api.crm.Records.CompanyDetail;
import com.customercompass.api.crm.Records.CompanySummary;
import com.customercompass.api.crm.Records.Task;
import com.customercompass.api.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The CRM read routes: the company explorer and the follow-up task list. */
@Tag(name = "CRM", description = "The sample CRM data (companies, deals, tasks).")
@RestController
@RequestMapping("/api/v1")
public class CrmController {

  private final CrmRepository repository;

  public CrmController(CrmRepository repository) {
    this.repository = repository;
  }

  @Operation(summary = "List companies", description = "All companies with a short summary.")
  @GetMapping("/companies")
  List<CompanySummary> companies() {
    return repository.listCompanies();
  }

  @Operation(
      summary = "Get a company",
      description = "One company with its deals; 404 if it does not exist.")
  @GetMapping("/companies/{company_id}")
  CompanyDetail company(@PathVariable("company_id") long companyId) {
    return repository
        .findCompany(companyId)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Company not found."));
  }

  @Operation(
      summary = "List tasks",
      description = "Follow-up tasks, optionally for one company (company_id).")
  @GetMapping("/tasks")
  List<Task> tasks(@RequestParam(name = "company_id", required = false) Long companyId) {
    return repository.listTasks(companyId);
  }
}
