package io.akka.usememos.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.usememos.domain.Memo;
import io.akka.usememos.domain.Visibility;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * SPEC-001 §3 — one memo's own lifecycle: capture (R1–R5), tag derivation on content change
 * (R6, §2), and creator-only mutation (R16, R17). Space-membership requirements for {@code
 * SPACE} visibility (R4) are enforced by the caller ({@link
 * io.akka.usememos.api.MemoEndpoint}) before a command reaches this entity — an entity cannot
 * call another entity to check membership itself.
 */
@Component(id = "memo")
public class MemoEntity extends EventSourcedEntity<Memo, MemoEntity.Event> {

  // R2 — the source enforces this as a byte count while its own message says "characters"
  // (question-log row 16); this port measures bytes and says so (SPEC-001 OD-3).
  static final int CONTENT_LENGTH_LIMIT_BYTES = 8192;

  public sealed interface Event {}

  @TypeName("memo-created")
  public record Created(
      String memoId,
      String creatorId,
      String content,
      Visibility visibility,
      String spaceId,
      List<String> tags,
      Instant at)
      implements Event {}

  @TypeName("memo-content-updated")
  public record ContentUpdated(String content, List<String> tags, Instant at) implements Event {}

  @TypeName("memo-visibility-updated")
  public record VisibilityUpdated(Visibility visibility, String spaceId, Instant at)
      implements Event {}

  @TypeName("memo-pinned-updated")
  public record PinnedUpdated(boolean pinned, Instant at) implements Event {}

  @TypeName("memo-archived")
  public record Archived(Instant at) implements Event {}

  public record Create(
      String creatorId, String content, Visibility visibility, String spaceId, Instant now) {}

  public record UpdateContent(String actorId, String content, Instant now) {}

  public record UpdateVisibility(
      String actorId, Visibility visibility, String spaceId, Instant now) {}

  public record SetPinned(String actorId, boolean pinned, Instant now) {}

  public record ArchiveMemo(String actorId, Instant now) {}

  @Override
  public Memo emptyState() {
    return Memo.empty();
  }

  public Effect<Done> create(Create command) {
    if (currentState().exists()) {
      return effects().error("Memo already exists");
    }
    if (command.creatorId() == null || command.creatorId().isBlank()) {
      return effects().error("A memo requires a creator"); // R1
    }
    String content = command.content() == null ? "" : command.content();
    if (utf8Length(content) > CONTENT_LENGTH_LIMIT_BYTES) {
      return effects().error("content too long (max " + CONTENT_LENGTH_LIMIT_BYTES + " bytes)"); // R2
    }
    Visibility visibility = command.visibility() == null ? Visibility.PRIVATE : command.visibility(); // R3
    if (visibility == Visibility.SPACE && (command.spaceId() == null || command.spaceId().isBlank())) {
      return effects().error("SPACE visibility requires a space"); // R4 (structural half)
    }
    String spaceId = visibility == Visibility.SPACE ? command.spaceId() : null;
    var event =
        new Created(
            commandContext().entityId(),
            command.creatorId(),
            content,
            visibility,
            spaceId,
            TagExtractor.extract(content),
            command.now());
    return effects().persist(event).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> updateContent(UpdateContent command) {
    Effect<Done> authError = requireOwner(command.actorId());
    if (authError != null) return authError;
    String content = command.content() == null ? "" : command.content();
    if (utf8Length(content) > CONTENT_LENGTH_LIMIT_BYTES) {
      return effects().error("content too long (max " + CONTENT_LENGTH_LIMIT_BYTES + " bytes)");
    }
    var event = new ContentUpdated(content, TagExtractor.extract(content), command.now()); // R6, R17
    return effects().persist(event).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> updateVisibility(UpdateVisibility command) {
    Effect<Done> authError = requireOwner(command.actorId());
    if (authError != null) return authError;
    if (command.visibility() == null) {
      return effects().error("A visibility value is required");
    }
    if (command.visibility() == Visibility.SPACE
        && (command.spaceId() == null || command.spaceId().isBlank())) {
      return effects().error("SPACE visibility requires a space"); // R17
    }
    String spaceId = command.visibility() == Visibility.SPACE ? command.spaceId() : null;
    var event = new VisibilityUpdated(command.visibility(), spaceId, command.now());
    return effects().persist(event).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> setPinned(SetPinned command) {
    Effect<Done> authError = requireOwner(command.actorId());
    if (authError != null) return authError;
    var event = new PinnedUpdated(command.pinned(), command.now());
    return effects().persist(event).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> archive(ArchiveMemo command) {
    Effect<Done> authError = requireOwner(command.actorId());
    if (authError != null) return authError;
    return effects().persist(new Archived(command.now())).thenReply(s -> Done.getInstance());
  }

  public ReadOnlyEffect<Memo> get() {
    if (!currentState().exists()) {
      return effects().error("Memo not found");
    }
    return effects().reply(currentState());
  }

  private <T> Effect<T> requireOwner(String actorId) {
    if (!currentState().exists()) {
      return effects().error("Memo not found");
    }
    if (actorId == null || !actorId.equals(currentState().creatorId())) {
      return effects().error("Only the creator may change this memo"); // R16
    }
    return null;
  }

  private static int utf8Length(String content) {
    return content.getBytes(StandardCharsets.UTF_8).length;
  }

  @Override
  public Memo applyEvent(Event event) {
    return switch (event) {
      case Created e ->
          Memo.created(e.memoId(), e.creatorId(), e.content(), e.visibility(), e.spaceId(), e.tags(), e.at());
      case ContentUpdated e -> currentState().withContent(e.content(), e.tags(), e.at());
      case VisibilityUpdated e -> currentState().withVisibility(e.visibility(), e.spaceId(), e.at());
      case PinnedUpdated e -> currentState().withPinned(e.pinned(), e.at());
      case Archived e -> currentState().withArchived(e.at());
    };
  }
}
