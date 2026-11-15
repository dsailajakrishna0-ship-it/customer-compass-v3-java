package com.customercompass.comparisons.common;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the CRM facts over HTTP from the application's own CRM routes ({@code GET
 * /api/v1/companies}: the list of companies, and {@code GET /api/v1/companies/{id}}: one company
 * with its deals), so a comparison backend never touches the database or the application's code.
 */
public final class CrmClient {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final String baseUrl;

  public CrmClient(String baseUrl) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  /** The companies array, as the application returns it: id, name, industry, relationship_status, counts. */
  public JsonNode companies() {
    JsonNode body = get("/api/v1/companies");
    if (!body.isArray()) {
      throw new Chat.UnavailableException("The CRM at " + baseUrl + " did not return a list.");
    }
    return body;
  }

  /** One company with its deals (id, name, amount, status open|won|lost, expected_close_date), or null when unknown. */
  public JsonNode company(int id) {
    JsonNode body = get("/api/v1/companies/" + id);
    return body.isObject() && body.has("deals") ? body : null;
  }

  private JsonNode get(String path) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10)).GET().build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404) {
        return JSON.createObjectNode();
      }
      if (response.statusCode() != 200) {
        throw new Chat.UnavailableException(
            "The CRM at " + baseUrl + " answered HTTP " + response.statusCode() + ".");
      }
      return JSON.readTree(response.body());
    } catch (IOException e) {
      throw new Chat.UnavailableException(
          "The CRM at " + baseUrl + " is not reachable. Start the application (just run).", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Chat.UnavailableException("Interrupted while reading the CRM.", e);
    }
  }
}
