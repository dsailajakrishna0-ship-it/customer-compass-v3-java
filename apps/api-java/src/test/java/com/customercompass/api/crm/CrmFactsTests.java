package com.customercompass.api.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.customercompass.api.crm.CompanySummary.CompanyRef;
import com.customercompass.api.crm.CompanySummary.DealFact;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The structured facts against the seeded demo data in a real Postgres. */
@Testcontainers
@SpringBootTest
class CrmFactsTests {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");

  @Autowired CrmFacts facts;

  @Test
  void companiesComeBackSortedByName() {
    List<CompanyRef> companies = facts.companies();

    assertThat(companies).hasSize(7);
    assertThat(companies).extracting(CompanyRef::name).isSorted();
    assertThat(companies.get(0)).isEqualTo(new CompanyRef(1, "Acme Fabrication"));
  }

  @Test
  void aSummaryHasTheStatusTheOpenDealsAndTheOutcomeCounts() {
    CompanySummary acme = facts.summary(1).orElseThrow();

    assertThat(acme.name()).isEqualTo("Acme Fabrication");
    assertThat(acme.industry()).isEqualTo("Manufacturing");
    assertThat(acme.relationshipStatus()).isEqualTo("customer");
    assertThat(acme.openDeals())
        .containsExactly(
            new DealFact(1, "Premium Operations Plan renewal", "48000.00", "open", "2026-10-15"));
    assertThat(acme.wonDealsCount()).isEqualTo(1);
    assertThat(acme.lostDealsCount()).isZero();
  }

  @Test
  void aLostDealIsCountedAndAnUnknownCompanyHasNoSummary() {
    assertThat(facts.summary(6).orElseThrow().lostDealsCount()).isEqualTo(1);
    assertThat(facts.summary(6).orElseThrow().openDeals()).isEmpty();
    assertThat(facts.summary(9999)).isEmpty();
  }
}
