package io.akka.usememos.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.akka.usememos.application.MemoReadAccess;
import io.akka.usememos.application.TagExtractor;
import io.akka.usememos.domain.Memo;
import io.akka.usememos.domain.ReadDecision;
import io.akka.usememos.domain.Visibility;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Not a correctness test — this is bench/workloads.json's port-side driver, run by hand
 * (`mvn test -Dtest=BenchmarkRunner`) to produce bench/port-answers-*.json for
 * toolkit/bench_probes.py and the same-answers comparison in bench/REPORT.md.
 */
class BenchmarkRunner {

  private static final Path BENCH = Path.of("..", "usememos-port", "bench");

  @Test
  void runTagExtractionWorkload() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    var workloads = (ArrayNode) mapper.readTree(BENCH.resolve("workloads.json").toFile());
    var cases = workloads.get(0).get("cases");

    var answers = new TreeMap<String, List<String>>();
    for (var c : cases) {
      String id = c.get("id").asText();
      String content = c.get("content").asText();
      answers.put(id, TagExtractor.extract(content));
    }

    mapper.writerWithDefaultPrettyPrinter()
        .writeValue(BENCH.resolve("port-answers-tags.json").toFile(), answers);
  }

  @Test
  void runReadAccessWorkload() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    var workloads = (ArrayNode) mapper.readTree(BENCH.resolve("workloads.json").toFile());
    var cases = workloads.get(1).get("cases");

    var answers = new TreeMap<String, String>();
    for (var c : cases) {
      String id = c.get("id").asText();
      Visibility visibility = Visibility.valueOf(c.get("visibility").asText());
      boolean archived = c.get("archived").asBoolean();
      boolean hasViewer = c.get("hasViewer").asBoolean();
      boolean viewerIsCreator = c.get("viewerIsCreator").asBoolean();
      boolean viewerIsSpaceMember = c.get("viewerIsSpaceMember").asBoolean();
      boolean allowAnonymousAccess = c.get("allowAnonymousAccess").asBoolean();

      String spaceId = visibility == Visibility.SPACE ? "space1" : null;
      Memo memo =
          new Memo(
              "m1", "creator", "content", visibility, spaceId, false, List.of(), archived,
              Instant.EPOCH, Instant.EPOCH);
      String viewerId = hasViewer ? (viewerIsCreator ? "creator" : "other") : null;

      ReadDecision decision =
          MemoReadAccess.evaluate(memo, viewerId, viewerIsSpaceMember, allowAnonymousAccess);
      answers.put(id, decision.name());
    }

    mapper.writerWithDefaultPrettyPrinter()
        .writeValue(BENCH.resolve("port-answers-access.json").toFile(), answers);
  }

  /**
   * Times {@link TagExtractor#extract} and {@link MemoReadAccess#evaluate} the same way the
   * source-side Go benchmarks do: several windows, many repetitions each, median taken (never
   * the minimum) -- toolkit/timing_check.py's rules.
   */
  @Test
  void timeTagExtractionAndReadAccess() throws Exception {
    String content = "#book/fiction/history #tag's contraction plain #a/b/c nested";
    int tagReps = 20_000;
    int accessReps = 20_000_000;
    int windows = 7;

    // Warm up the JIT before any window is timed -- an untimed warm-up run, not counted.
    int sink = 0;
    for (int i = 0; i < tagReps; i++) sink += TagExtractor.extract(content).size();

    List<Long> tagWindowNanos = new ArrayList<>();
    for (int w = 0; w < windows; w++) {
      long start = System.nanoTime();
      for (int i = 0; i < tagReps; i++) {
        sink += TagExtractor.extract(content).size();
      }
      tagWindowNanos.add(System.nanoTime() - start);
    }

    // A loop-invariant call over constant arguments is exactly as free for the JIT to hoist
    // and constant-fold as it is for the JIT to delete an unread result -- the first version
    // of this benchmark called MemoReadAccess.evaluate with the same memo and viewer every
    // iteration and measured 0ns flat, the loop itself having been proven to always produce
    // the same answer. Cycling over genuinely different inputs (the same visibility values
    // this workload already covers) is what makes each call a real computation again.
    Memo[] memos = {
      new Memo("m1", "creator", "c", Visibility.PRIVATE, null, false, List.of(), false, Instant.EPOCH, Instant.EPOCH),
      new Memo("m2", "creator", "c", Visibility.PROTECTED, null, false, List.of(), false, Instant.EPOCH, Instant.EPOCH),
      new Memo("m3", "creator", "c", Visibility.PUBLIC, null, false, List.of(), false, Instant.EPOCH, Instant.EPOCH),
      new Memo("m4", "creator", "c", Visibility.SPACE, "space1", false, List.of(), false, Instant.EPOCH, Instant.EPOCH),
    };
    String[] viewers = {"creator", "other", null};

    for (int i = 0; i < accessReps; i++) {
      sink +=
          MemoReadAccess.evaluate(
                  memos[i % memos.length], viewers[i % viewers.length], i % 2 == 0, i % 3 == 0)
              .ordinal();
    }

    List<Long> accessWindowNanos = new ArrayList<>();
    for (int w = 0; w < windows; w++) {
      long start = System.nanoTime();
      for (int i = 0; i < accessReps; i++) {
        sink +=
            MemoReadAccess.evaluate(
                    memos[i % memos.length], viewers[i % viewers.length], i % 2 == 0, i % 3 == 0)
                .ordinal();
      }
      accessWindowNanos.add(System.nanoTime() - start);
    }
    if (sink == Integer.MIN_VALUE) throw new AssertionError(); // never true; keeps sink live

    Map<String, Object> timing = new LinkedHashMap<>();
    timing.put("tag-extraction", timingEntry(tagWindowNanos, tagReps));
    timing.put("read-access", timingEntry(accessWindowNanos, accessReps));

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("timing", timing);

    ObjectMapper mapper = new ObjectMapper();
    mapper.writerWithDefaultPrettyPrinter()
        .writeValue(BENCH.resolve("port-timings.json").toFile(), out);
  }

  private static Map<String, Object> timingEntry(List<Long> windowNanos, int reps) {
    List<Long> sorted = new ArrayList<>(windowNanos);
    Collections.sort(sorted);
    long median = sorted.get(sorted.size() / 2);
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("repetitions", reps);
    entry.put("windows", windowNanos.size());
    entry.put("windowNanos", median);
    entry.put("nanosPerRun", median / (double) reps);
    return entry;
  }
}
