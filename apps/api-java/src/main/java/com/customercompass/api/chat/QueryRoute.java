package com.customercompass.api.chat;

import com.customercompass.api.crm.CompanySummary.CompanyRef;

/** Where a question goes: CRM facts, document search, or both, and which company it names. */
public record QueryRoute(boolean wantsStructured, boolean wantsRag, CompanyRef company) {}
