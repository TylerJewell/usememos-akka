package io.akka.usememos.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.usememos.domain.Visibility;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The read side memos are listed from. R14 — the same {@link MemoReadAccess} decision every
 * single-memo read uses is applied here in {@link io.akka.usememos.api.MemoEndpoint}, over the
 * unfiltered rows this view returns; the view itself does not filter by visibility, so there is
 * exactly one place the read-access rule is evaluated, not two.
 *
 * <p>The row type wraps {@code spaceId} as {@code Optional<String>} rather than reusing {@link
 * io.akka.usememos.domain.Memo} directly — a View's row schema rejects a plain nullable {@code
 * String} field with "expected to be non-optional but is missing" the first time a row with a
 * null value (any non-{@code SPACE} memo) is written, confirmed by running it: the first
 * PRIVATE memo created crashed the view's update stream into a restart loop and every list
 * query silently returned nothing until this was fixed.
 */
@Component(id = "memos-view")
public class MemosView extends View {

  public record MemoEntry(
      String memoId,
      String creatorId,
      String content,
      Visibility visibility,
      Optional<String> spaceId,
      boolean pinned,
      List<String> tags,
      boolean archived,
      Instant createdAt,
      Instant updatedAt) {}

  public record MemoRows(List<MemoEntry> memos) {}

  @Consume.FromEventSourcedEntity(MemoEntity.class)
  public static class MemosUpdater extends TableUpdater<MemoEntry> {
    public Effect<MemoEntry> onEvent(MemoEntity.Event event) {
      return effects().updateRow(applyEvent(rowState(), event));
    }

    private MemoEntry applyEvent(MemoEntry current, MemoEntity.Event event) {
      return switch (event) {
        case MemoEntity.Created e ->
            new MemoEntry(
                e.memoId(), e.creatorId(), e.content(), e.visibility(),
                Optional.ofNullable(e.spaceId()), false, e.tags(), false, e.at(), e.at());
        case MemoEntity.ContentUpdated e ->
            new MemoEntry(
                current.memoId(), current.creatorId(), e.content(), current.visibility(),
                current.spaceId(), current.pinned(), e.tags(), current.archived(),
                current.createdAt(), e.at());
        case MemoEntity.VisibilityUpdated e ->
            new MemoEntry(
                current.memoId(), current.creatorId(), current.content(), e.visibility(),
                Optional.ofNullable(e.spaceId()), current.pinned(), current.tags(),
                current.archived(), current.createdAt(), e.at());
        case MemoEntity.PinnedUpdated e ->
            new MemoEntry(
                current.memoId(), current.creatorId(), current.content(), current.visibility(),
                current.spaceId(), e.pinned(), current.tags(), current.archived(),
                current.createdAt(), e.at());
        case MemoEntity.Archived e ->
            new MemoEntry(
                current.memoId(), current.creatorId(), current.content(), current.visibility(),
                current.spaceId(), current.pinned(), current.tags(), true, current.createdAt(),
                e.at());
      };
    }
  }

  @Query("SELECT * AS memos FROM memos_view ORDER BY createdAt DESC")
  public QueryEffect<MemoRows> all() {
    return queryResult();
  }
}
