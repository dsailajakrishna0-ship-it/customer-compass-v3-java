package com.customercompass.api.crm;

import com.customercompass.api.crm.Records.CompanyDetail;
import com.customercompass.api.crm.Records.CompanySummary;
import com.customercompass.api.crm.Records.Task;
import com.customercompass.api.web.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The CRM read routes: the company explorer and the follow-up task list. */
@RestController
@RequestMapping("/api/v1")
public class CrmController {

  private final CrmRepository repository;

  public CrmController(CrmRepository repository) {
    this.repository = repository;
  }

  @GetMapping("/companies")
  List<CompanySummary> companies() {
    return repository.listCompanies();
  }

  @GetMapping("/companies/{company_id}")
  CompanyDetail company(@PathVariable("company_id") long companyId) {
    return repository
        .findCompany(companyId)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Company not found."));
  }

  @GetMapping("/tasks")
  List<Task> tasks(@RequestParam(name = "company_id", required = false) Long companyId) {
    return repository.listTasks(companyId);
  }
}
