package com.customercompass.api.rag;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class VectorIndexTests {

  @Test
  void aGrpcUnavailableAnywhereInTheCauseChainMeansQdrantIsDown() {
    var unavailable =
        new StatusRuntimeException(Status.UNAVAILABLE.withDescription("io exception"));
    // Spring AI wraps the gRPC error in an ExecutionException and then in a RuntimeException
    var wrapped = new RuntimeException(new ExecutionException(unavailable));

    assertThat(VectorIndex.isUnavailable(wrapped)).isTrue();
    assertThat(VectorIndex.isUnavailable(unavailable)).isTrue();
  }

  @Test
  void otherFailuresAreNotMistakenForQdrantBeingDown() {
    assertThat(VectorIndex.isUnavailable(new IllegalStateException("something else"))).isFalse();
    assertThat(
            VectorIndex.isUnavailable(
                new RuntimeException(new StatusRuntimeException(Status.INVALID_ARGUMENT))))
        .isFalse();
    assertThat(VectorIndex.isUnavailable(null)).isFalse();
  }

  @Test
  void theMessageNamesTheCommandThatStartsIt() {
    assertThat(VectorIndex.UNAVAILABLE_MESSAGE).contains("just qdrant").contains("not reachable");
  }
}
