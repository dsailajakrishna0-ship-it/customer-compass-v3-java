package com.customercompass.api.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Keyword (BM25-style) search vectors, so Qdrant can match exact words as well as meaning (the
 * Python edition's {@code sparse.py}, same maths and the same word hash, so both editions index a
 * word under the same number).
 *
 * <p>A dense embedding finds text that means the same thing but can blur exact terms: a deal name,
 * an abbreviation, a clause number. A sparse vector is the opposite: one number per word. Per chunk
 * we store BM25's term-frequency part, {@code tf * (k1 + 1) / (tf + k1 * (1 - b + b * length /
 * average))}; the collection's IDF modifier multiplies each word by how rare it is across all
 * chunks. TF here times IDF in Qdrant is BM25.
 *
 * <p>Words become numbers with a stable hash (CRC32), so a word always gets the same index across
 * runs and machines. Two words can collide; with a 32-bit space and a small corpus that only blurs
 * a score.
 */
public final class Sparse {

  /** BM25 constants: how fast repeats stop helping, and how strongly length is corrected. */
  static final double K1 = 1.2;

  static final double B = 0.75;

  /** A 600-character chunk holds about 100 words. */
  static final double AVERAGE_CHUNK_WORDS = 100;

  private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

  private Sparse() {}

  /** Indices and weights of one sparse vector, in matching order. */
  public record Vector(List<Integer> indices, List<Float> values) {}

  /** Lower-cased words and numbers; punctuation is dropped. */
  public static List<String> tokenize(String text) {
    var words = new ArrayList<String>();
    Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
    while (m.find()) {
      words.add(m.group());
    }
    return words;
  }

  /** The word's slot in the sparse vector: a stable 32-bit hash, as an int bit pattern. */
  public static int termIndex(String term) {
    var crc = new CRC32();
    crc.update(term.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return (int) crc.getValue();
  }

  /** The vector stored with a chunk: BM25 term-frequency weights. */
  public static Vector documentVector(String text) {
    List<String> words = tokenize(text);
    int length = words.size();
    var counts = new LinkedHashMap<String, Integer>();
    for (String w : words) {
      counts.merge(w, 1, Integer::sum);
    }
    var weights = new LinkedHashMap<Integer, Double>();
    for (Map.Entry<String, Integer> e : counts.entrySet()) {
      int count = e.getValue();
      double weight = count * (K1 + 1) / (count + K1 * (1 - B + B * length / AVERAGE_CHUNK_WORDS));
      weights.merge(termIndex(e.getKey()), weight, Double::sum);
    }
    var indices = new ArrayList<Integer>();
    var values = new ArrayList<Float>();
    weights.forEach(
        (i, w) -> {
          indices.add(i);
          values.add(w.floatValue());
        });
    return new Vector(indices, values);
  }

  /** The vector for a question: each distinct word once, weight 1. */
  public static Vector queryVector(String text) {
    var indices = new TreeSet<Integer>();
    for (String term : tokenize(text)) {
      indices.add(termIndex(term));
    }
    var list = new ArrayList<>(indices);
    return new Vector(list, list.stream().map(i -> 1.0f).toList());
  }
}
