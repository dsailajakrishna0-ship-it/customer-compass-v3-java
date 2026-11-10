package com.customercompass.api.llm;

import com.customercompass.api.llm.ModelCatalog.Model;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Reads https://openrouter.ai/api/v1/models (public, no key needed). */
@Component
class OpenRouterModelList implements ModelListSource {

  private final RestClient http;

  OpenRouterModelList() {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.http =
        RestClient.builder()
            .baseUrl("https://openrouter.ai/api/v1")
            .requestFactory(factory)
            .build();
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<Model> fetchAll() {
    Map<String, Object> body = http.get().uri("/models").retrieve().body(Map.class);
    List<Map<String, Object>> data =
        body == null ? List.of() : (List<Map<String, Object>>) body.get("data");
    var models = new ArrayList<Model>();
    for (Map<String, Object> m : data == null ? List.<Map<String, Object>>of() : data) {
      models.add(new Model((String) m.get("id"), (String) m.get("name")));
    }
    return models;
  }
}
