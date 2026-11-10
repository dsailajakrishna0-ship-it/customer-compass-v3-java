package com.customercompass.api.llm;

import com.customercompass.api.llm.ModelCatalog.Model;
import com.customercompass.api.llm.ModelCatalog.OpenRouter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/** Reads models.yaml once at start-up; MODELS_FILE points at another copy. */
@Configuration
public class ModelCatalogLoader {

  @Bean
  ModelCatalog modelCatalog(@Value("${llm.models-file:}") String modelsFile) throws IOException {
    try (InputStream in =
        modelsFile.isBlank()
            ? new ClassPathResource("models.yaml").getInputStream()
            : Files.newInputStream(Path.of(modelsFile))) {
      return parse(in);
    }
  }

  @SuppressWarnings("unchecked")
  static ModelCatalog parse(InputStream in) {
    Map<String, Object> root = new Yaml().load(in);
    Map<String, Object> or = (Map<String, Object>) root.get("openrouter");
    List<Model> known = new ArrayList<>();
    for (Map<String, String> m : (List<Map<String, String>>) or.get("last_known_good")) {
      known.add(new Model(m.get("id"), m.get("name")));
    }
    return new ModelCatalog(
        new OpenRouter(
            (String) or.get("default"),
            List.copyOf((List<String>) or.get("fallback_chain")),
            List.copyOf(known)));
  }
}
