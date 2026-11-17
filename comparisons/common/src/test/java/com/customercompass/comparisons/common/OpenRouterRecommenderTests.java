package com.customercompass.comparisons.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OpenRouterRecommenderTests {

  private static String reply(String content) {
    return "{\"choices\":[{\"message\":{\"content\":" + tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(content) + "}}]}";
  }

  @Test
  void aPlainJsonObjectIsParsed() {
    var r = OpenRouterRecommender.parse(reply("{\"action\": \"Call them.\", \"rationale\": \"Because.\"}"));
    assertThat(r.action()).isEqualTo("Call them.");
    assertThat(r.rationale()).isEqualTo("Because.");
  }

  @Test
  void aCodeFenceAndWhitespaceAreStripped() {
    var r = OpenRouterRecommender.parse(reply("  ```json\n{\"action\": \" Call them. \", \"rationale\": \"\"}\n```  "));
    assertThat(r.action()).isEqualTo("Call them.");
    assertThat(r.rationale()).isEmpty();
  }

  @Test
  void theLimitsAreInclusive() {
    OpenRouterRecommender.parse(reply("{\"action\": \"" + "a".repeat(200) + "\", \"rationale\": \"" + "r".repeat(400) + "\"}"));
    assertThatThrownBy(() -> OpenRouterRecommender.parse(reply("{\"action\": \"" + "a".repeat(201) + "\", \"rationale\": \"r\"}")))
        .isInstanceOf(Agent.UnavailableException.class);
    assertThatThrownBy(() -> OpenRouterRecommender.parse(reply("{\"action\": \"a\", \"rationale\": \"" + "r".repeat(401) + "\"}")))
        .isInstanceOf(Agent.UnavailableException.class);
  }

  @Test
  void anythingElseIsNotAUsableRecommendation() {
    for (String content : new String[] {"", "hello", "[]", "{}", "{\"action\": 5}", "{\"action\": null}", "{\"action\": \"  \"}"}) {
      assertThatThrownBy(() -> OpenRouterRecommender.parse(reply(content))).as(content).hasMessage("The model did not return a usable recommendation.");
    }
    assertThatThrownBy(() -> OpenRouterRecommender.parse("not json")).hasMessage("The model did not return a usable recommendation.");
    assertThatThrownBy(() -> OpenRouterRecommender.parse("{\"choices\":[]}")).hasMessage("The model did not return a usable recommendation.");
  }
}
