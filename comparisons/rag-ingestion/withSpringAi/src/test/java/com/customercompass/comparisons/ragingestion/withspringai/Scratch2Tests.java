package com.customercompass.comparisons.ragingestion.withspringai;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Points;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;

class Scratch2Tests {

  private GoogleGenAiTextEmbeddingModel model(String url, int dims, Integer attempts) {
    var http = HttpOptions.builder().baseUrl(url);
    if (attempts != null) http.retryOptions(HttpRetryOptions.builder().attempts(attempts));
    Client client = Client.builder().apiKey("secret-key-123").httpOptions(http.build()).build();
    var details = GoogleGenAiEmbeddingConnectionDetails.builder().genAiClient(client).build();
    var options = GoogleGenAiTextEmbeddingOptions.builder().model("gemini-embedding-001").dimensions(dims).taskType(GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_DOCUMENT).build();
    return new GoogleGenAiTextEmbeddingModel(details, options);
  }

  private static FakeHttp geminiCounting(int dims) throws Exception {
    return new FakeHttp(request -> {
      int texts = request.body().split("\"parts\"", -1).length - 1;
      var v = new StringBuilder("[");
      for (int i = 0; i < dims; i++) v.append(i == 0 ? "" : ",").append("0.5");
      v.append("]");
      var b = new StringBuilder("{\"embeddings\":[");
      for (int i = 0; i < texts; i++) b.append(i == 0 ? "" : ",").append("{\"values\":").append(v).append("}");
      return new FakeHttp.Reply(200, b.append("]}").toString());
    });
  }

  @Test
  void gemini() throws Exception {
    try (var g = geminiCounting(4)) {
      var out = model(g.url(), 4, null).embed(List.of("one", "two"));
      System.out.println("MEASURE2 2 texts vectors=" + out.size() + " requests=" + g.requests.size());
      System.out.println("MEASURE2 FULL BODY " + g.requests.get(0).body());
    }
    try (var g = geminiCounting(4)) {
      var out = model(g.url(), 4, null).embed(Collections.nCopies(250, "text"));
      System.out.println("MEASURE2 250 texts vectors=" + out.size() + " requests=" + g.requests.size());
    }
    for (int status : new int[] {429, 500}) {
      try (var g = new FakeHttp(r -> new FakeHttp.Reply(status, "{\"error\":\"x\"}"))) {
        long t0 = System.currentTimeMillis();
        try { model(g.url(), 4, 1).embed(List.of("one")); } catch (Exception e) {
          System.out.println("MEASURE2 attempts=1 status " + status + " requests=" + g.requests.size() + " ms=" + (System.currentTimeMillis() - t0) + " msg=" + e.getMessage());
        }
      }
    }
  }

  @Test
  void overlap() {
    var sb = new StringBuilder();
    for (int i = 1; i <= 120; i++) sb.append("Sentence number ").append(i).append(" ends here. ");
    var chunks = TokenTextSplitter.builder().withChunkSize(60).build().apply(List.of(new Document(sb.toString())));
    System.out.println("MEASURE2 overlap chunks=" + chunks.size());
    for (int i = 0; i < Math.min(3, chunks.size()); i++) {
      String t = chunks.get(i).getText();
      System.out.println("MEASURE2   chunk " + i + " len=" + t.length() + " first=[" + t.substring(0, 28) + "] last=[" + t.substring(t.length() - 28) + "]");
    }
  }

  /** a fake embedding model with a fixed size, optionally failing */
  static final class Fake extends AbstractEmbeddingModel {
    final int dims; boolean fail; int calls; int texts;
    Fake(int dims) { this.dims = dims; }
    @Override public EmbeddingResponse call(EmbeddingRequest request) {
      calls++; texts += request.getInstructions().size();
      if (fail) throw new IllegalStateException("embedding down");
      var out = new ArrayList<Embedding>();
      int i = 0;
      for (String t : request.getInstructions()) { float[] v = new float[dims]; java.util.Arrays.fill(v, 0.5f); out.add(new Embedding(v, i++)); }
      return new EmbeddingResponse(out);
    }
    @Override public float[] embed(Document document) { float[] v = new float[dims]; java.util.Arrays.fill(v, 0.5f); return v; }
    @Override public int dimensions() { return dims; }
  }

  @Test
  void qdrant() throws Exception {
    var client = new QdrantClient(QdrantGrpcClient.newBuilder("localhost", 36334, false).build());
    String c = "scratch_j82b";
    if (Boolean.TRUE.equals(client.collectionExistsAsync(c).get())) client.deleteCollectionAsync(c).get();
    var fake = new Fake(4);
    var store = QdrantVectorStore.builder(client, fake).collectionName(c).initializeSchema(true).build();
    store.afterPropertiesSet();
    var info = client.getCollectionInfoAsync(c).get();
    System.out.println("MEASURE2 created collection size=" + info.getConfig().getParams().getVectorsConfig().getParams().getSize() + " distance=" + info.getConfig().getParams().getVectorsConfig().getParams().getDistance());
    var docs = List.of(
        new Document(UUID.nameUUIDFromBytes("a-0".getBytes()).toString(), "alpha text", Map.of("title", "A", "source", "a.md", "chunkIndex", 0)),
        new Document(UUID.nameUUIDFromBytes("a-1".getBytes()).toString(), "beta text", Map.of("title", "A", "source", "a.md", "chunkIndex", 1)));
    store.add(docs);
    store.add(docs);
    System.out.println("MEASURE2 same ids added twice -> count=" + client.countAsync(c, null, true).get() + " embedCalls=" + fake.calls + " texts=" + fake.texts);
    store.add(List.of(new Document("gamma text", Map.of("title", "G")), new Document("gamma text", Map.of("title", "G"))));
    System.out.println("MEASURE2 + two random-id docs -> count=" + client.countAsync(c, null, true).get());
    var scroll = client.scrollAsync(Points.ScrollPoints.newBuilder().setCollectionName(c).setLimit(1).setWithPayload(io.qdrant.client.WithPayloadSelectorFactory.enable(true)).build()).get();
    var p = scroll.getResult(0);
    System.out.println("MEASURE2 payload keys=" + p.getPayloadMap().keySet() + " idKind=" + p.getId().getPointIdOptionsCase());
    fake.fail = true;
    try { store.add(List.of(new Document("delta text", Map.of("title", "D")))); } catch (Exception e) { System.out.println("MEASURE2 failed embedding threw " + e.getClass().getSimpleName() + " count after=" + client.countAsync(c, null, true).get()); }
    // 250 docs: embed batching by the store
    fake.fail = false; fake.calls = 0; fake.texts = 0;
    var many = new ArrayList<Document>();
    for (int i = 0; i < 250; i++) many.add(new Document(UUID.nameUUIDFromBytes(("m" + i).getBytes()).toString(), "text " + i, Map.of("i", i)));
    store.add(many);
    System.out.println("MEASURE2 250 docs: embedding calls=" + fake.calls + " texts=" + fake.texts + " count=" + client.countAsync(c, null, true).get());
    client.deleteCollectionAsync(c).get();
    client.close();
  }
}
