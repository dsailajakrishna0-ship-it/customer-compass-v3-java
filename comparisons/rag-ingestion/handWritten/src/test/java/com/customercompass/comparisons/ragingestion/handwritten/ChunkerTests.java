package com.customercompass.comparisons.ragingestion.handwritten;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChunkerTests {

  @Test
  void aShortTextIsOneChunk() {
    var chunks = Chunker.chunk("A short note.");
    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).content()).isEqualTo("A short note.");
    assertThat(chunks.get(0).index()).isZero();
  }

  @Test
  void aBlankTextHasNoChunks() {
    assertThat(Chunker.chunk("   \n  ")).isEmpty();
  }

  @Test
  void windowsOverlapByOneHundredCharacters() {
    String text = "x".repeat(1_000);
    var chunks = Chunker.chunk(text);
    // 600 long, a step of 500: starts at 0 and 500
    assertThat(chunks).hasSize(2);
    assertThat(chunks.get(0).charStart()).isZero();
    assertThat(chunks.get(0).charEnd()).isEqualTo(600);
    assertThat(chunks.get(1).charStart()).isEqualTo(500);
    assertThat(chunks.get(1).charEnd()).isEqualTo(1_000);
  }

  @Test
  void chunkNumbersRunOnInOrder() {
    var chunks = Chunker.chunk("y".repeat(2_000));
    for (int i = 0; i < chunks.size(); i++) {
      assertThat(chunks.get(i).index()).isEqualTo(i);
    }
  }

  @Test
  void sizesCountCodePointsNotUtf16Units() {
    // 300 emoji are 600 UTF-16 units but 300 characters: one chunk
    assertThat(Chunker.chunk("😀".repeat(300))).hasSize(1);
  }

  @Test
  void badSizesAreRefused() {
    assertThatThrownBy(() -> Chunker.chunk("text", 0, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Chunker.chunk("text", 10, 10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Chunker.chunk("text", 10, -1)).isInstanceOf(IllegalArgumentException.class);
  }
}
