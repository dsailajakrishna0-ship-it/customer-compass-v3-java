package com.customercompass.api.kb;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The extra knowledge bases (a Java-edition extension, ADR 0017). The CRM documents are always the
 * knowledge base {@code crm}; each entry here is another corpus with its own Qdrant collection.
 *
 * @param extra the additional knowledge bases
 */
@ConfigurationProperties("knowledge-bases")
public record KnowledgeBaseProperties(@DefaultValue List<Source> extra) {

  /**
   * One extra knowledge base.
   *
   * @param id the short name used in routes and in the collection name (letters, digits, dashes)
   * @param label a human name
   * @param description what is in it: this is what a router (or a model) reads to decide whether to
   *     search it
   * @param sourceDir the folder of documents; empty means {@code sample-data/knowledge-bases/<id>}
   *     above the working directory
   */
  public record Source(
      String id, String label, String description, @DefaultValue("") String sourceDir) {}
}
