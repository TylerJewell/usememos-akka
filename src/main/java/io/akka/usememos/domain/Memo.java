package io.akka.usememos.domain;

import java.time.Instant;
import java.util.List;

/** SPEC-001 §2 — one captured note. {@code null} fields mean the entity does not exist yet. */
public record Memo(
    String memoId,
    String creatorId,
    String content,
    Visibility visibility,
    String spaceId,
    boolean pinned,
    List<String> tags,
    boolean archived,
    Instant createdAt,
    Instant updatedAt) {

  public static Memo empty() {
    return new Memo(null, null, null, null, null, false, List.of(), false, null, null);
  }

  public static Memo created(
      String memoId,
      String creatorId,
      String content,
      Visibility visibility,
      String spaceId,
      List<String> tags,
      Instant at) {
    return new Memo(memoId, creatorId, content, visibility, spaceId, false, tags, false, at, at);
  }

  public boolean exists() {
    return memoId != null;
  }

  public Memo withContent(String content, List<String> tags, Instant at) {
    return new Memo(
        memoId, creatorId, content, visibility, spaceId, pinned, tags, archived, createdAt, at);
  }

  public Memo withVisibility(Visibility visibility, String spaceId, Instant at) {
    return new Memo(
        memoId, creatorId, content, visibility, spaceId, pinned, tags, archived, createdAt, at);
  }

  public Memo withPinned(boolean pinned, Instant at) {
    return new Memo(
        memoId, creatorId, content, visibility, spaceId, pinned, tags, archived, createdAt, at);
  }

  public Memo withArchived(Instant at) {
    return new Memo(
        memoId, creatorId, content, visibility, spaceId, pinned, tags, true, createdAt, at);
  }
}
