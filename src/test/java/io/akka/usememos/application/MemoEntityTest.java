package io.akka.usememos.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.usememos.domain.Visibility;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 R1–R6, R16, R17 — one memo's own lifecycle. */
class MemoEntityTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-01-01T00:00:05Z");

  private static EventSourcedTestKit<
          io.akka.usememos.domain.Memo, MemoEntity.Event, MemoEntity>
      kit(String id) {
    return EventSourcedTestKit.of(id, MemoEntity::new);
  }

  @Test
  void createRequiresACreator() {
    var testKit = kit("m1");
    var result =
        testKit
            .method(MemoEntity::create)
            .invoke(new MemoEntity.Create("", "hello", null, null, T0));
    assertThat(result.isError()).isTrue();
  }

  @Test
  void visibilityDefaultsToPrivateAndTagsAreDerived() {
    var testKit = kit("m1");
    testKit
        .method(MemoEntity::create)
        .invoke(new MemoEntity.Create("alice", "note about #work", null, null, T0));
    assertThat(testKit.getState().visibility()).isEqualTo(Visibility.PRIVATE); // R3
    assertThat(testKit.getState().tags()).containsExactly("work"); // R6
  }

  @Test
  void contentOverTheByteLimitIsRejected() {
    var testKit = kit("m1");
    String tooLong = "a".repeat(MemoEntity.CONTENT_LENGTH_LIMIT_BYTES + 1);
    var result =
        testKit
            .method(MemoEntity::create)
            .invoke(new MemoEntity.Create("alice", tooLong, null, null, T0));
    assertThat(result.isError()).isTrue(); // R2
  }

  @Test
  void spaceVisibilityRequiresASpaceId() {
    var testKit = kit("m1");
    var result =
        testKit
            .method(MemoEntity::create)
            .invoke(new MemoEntity.Create("alice", "hi", Visibility.SPACE, null, T0));
    assertThat(result.isError()).isTrue(); // R4 structural half
  }

  @Test
  void onlyTheCreatorMayUpdateContentOrVisibilityOrPinned() {
    var testKit = kit("m1");
    testKit.method(MemoEntity::create).invoke(new MemoEntity.Create("alice", "hi", null, null, T0));

    assertThat(
            testKit
                .method(MemoEntity::updateContent)
                .invoke(new MemoEntity.UpdateContent("bob", "new", T1))
                .isError())
        .isTrue(); // R16
    assertThat(
            testKit
                .method(MemoEntity::updateVisibility)
                .invoke(new MemoEntity.UpdateVisibility("bob", Visibility.PUBLIC, null, T1))
                .isError())
        .isTrue();
    assertThat(
            testKit.method(MemoEntity::setPinned).invoke(new MemoEntity.SetPinned("bob", true, T1)).isError())
        .isTrue();
  }

  @Test
  void changingContentRecomputesTags() {
    var testKit = kit("m1");
    testKit
        .method(MemoEntity::create)
        .invoke(new MemoEntity.Create("alice", "#old", null, null, T0));
    testKit
        .method(MemoEntity::updateContent)
        .invoke(new MemoEntity.UpdateContent("alice", "#new content", T1));
    assertThat(testKit.getState().tags()).containsExactly("new"); // R6, R17
    assertThat(testKit.getState().updatedAt()).isEqualTo(T1);
  }

  @Test
  void pinnedIsIndependentOfContentAndTags() {
    var testKit = kit("m1");
    testKit.method(MemoEntity::create).invoke(new MemoEntity.Create("alice", "hi", null, null, T0));
    testKit.method(MemoEntity::setPinned).invoke(new MemoEntity.SetPinned("alice", true, T1));
    assertThat(testKit.getState().pinned()).isTrue();
    assertThat(testKit.getState().content()).isEqualTo("hi");
  }

  @Test
  void archivingIsCreatorOnlyAndSticks() {
    var testKit = kit("m1");
    testKit.method(MemoEntity::create).invoke(new MemoEntity.Create("alice", "hi", null, null, T0));
    testKit.method(MemoEntity::archive).invoke(new MemoEntity.ArchiveMemo("alice", T1));
    assertThat(testKit.getState().archived()).isTrue();
  }
}
