package com.customercompass.api.llm;

import com.customercompass.api.llm.ModelCatalog.Model;
import java.util.List;

/** Where the live model list comes from (OpenRouter's public /models). A seam for tests. */
public interface ModelListSource {

  /** Every model OpenRouter lists. Throws when the list cannot be fetched. */
  List<Model> fetchAll();
}
