package io.akka.usememos.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Patch;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import io.akka.usememos.application.InstanceSettingsEntity;
import io.akka.usememos.application.MemoEntity;
import io.akka.usememos.application.MemoReadAccess;
import io.akka.usememos.application.MemosView;
import io.akka.usememos.application.SpaceEntity;
import io.akka.usememos.domain.Memo;
import io.akka.usememos.domain.ReadDecision;
import io.akka.usememos.domain.Space;
import io.akka.usememos.domain.Visibility;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SPEC-001 — capture, tag derivation (server-side, always — R6), and read-visibility (R12–R17)
 * over memos. Identity is a plain {@code X-User-Id} header — this slice has no authentication
 * capability of its own (SPEC-001 §1); a blank/missing header means an anonymous caller.
 */
@HttpEndpoint("/memos")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class MemoEndpoint extends AbstractHttpEndpoint {

  public record CreateRequest(String content, String visibility, String spaceId) {}

  public record UpdateContentRequest(String content) {}

  public record UpdateVisibilityRequest(String visibility, String spaceId) {}

  public record SetPinnedRequest(boolean pinned) {}

  public record MemoResponse(
      String memoId,
      String creatorId,
      String content,
      String visibility,
      String spaceId,
      boolean pinned,
      List<String> tags,
      boolean archived,
      String createdAt,
      String updatedAt) {}

  public record MemoListResponse(List<MemoResponse> memos) {}

  private final ComponentClient componentClient;

  public MemoEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("")
  public HttpResponse create(CreateRequest request) {
    String actorId = viewerId().orElse(null);
    if (actorId == null || actorId.isBlank()) {
      return unauthenticated("A creator is required");
    }
    Visibility visibility = parseVisibility(request.visibility());
    if (request.visibility() != null && visibility == null) {
      return badRequest("Unknown visibility: " + request.visibility());
    }
    if (visibility == Visibility.SPACE) {
      HttpResponse spaceCheck = requireActiveMember(request.spaceId(), actorId);
      if (spaceCheck != null) return spaceCheck;
    }
    String memoId = UUID.randomUUID().toString();
    try {
      componentClient
          .forEventSourcedEntity(memoId)
          .method(MemoEntity::create)
          .invoke(
              new MemoEntity.Create(actorId, request.content(), visibility, request.spaceId(), Instant.now()));
    } catch (Exception e) {
      return badRequest(e.getMessage());
    }
    Memo created = fetchMemo(memoId);
    return HttpResponses.created(toApi(created), "/memos/" + memoId);
  }

  @Get("/{memoId}")
  public HttpResponse get(String memoId) {
    Memo memo = fetchMemo(memoId);
    if (memo == null) {
      return HttpResponses.notFound("Memo not found");
    }
    ReadDecision decision = decide(memo);
    return switch (decision) {
      case ALLOWED -> HttpResponses.ok(toApi(memo));
      case NOT_FOUND -> HttpResponses.notFound("Memo not found");
      case UNAUTHENTICATED -> unauthenticated("Sign in to read this memo");
      case PERMISSION -> forbidden("Not allowed to read this memo");
    };
  }

  @Get("")
  public HttpResponse list() {
    MemosView.MemoRows rows =
        componentClient.forView().method(MemosView::all).invoke();
    Map<String, Space> spaceCache = new HashMap<>();
    boolean allowAnonymous = fetchAllowAnonymousAccess();
    List<MemoResponse> visible =
        rows.memos().stream()
            .map(MemoEndpoint::fromRow)
            .filter(m -> decide(m, spaceCache, allowAnonymous) == ReadDecision.ALLOWED)
            .map(this::toApi)
            .toList();
    return HttpResponses.ok(new MemoListResponse(visible));
  }

  private static Memo fromRow(MemosView.MemoEntry row) {
    return new Memo(
        row.memoId(), row.creatorId(), row.content(), row.visibility(),
        row.spaceId().orElse(null), row.pinned(), row.tags(), row.archived(), row.createdAt(),
        row.updatedAt());
  }

  @Patch("/{memoId}/content")
  public HttpResponse updateContent(String memoId, UpdateContentRequest request) {
    String actorId = viewerId().orElse(null);
    try {
      componentClient
          .forEventSourcedEntity(memoId)
          .method(MemoEntity::updateContent)
          .invoke(new MemoEntity.UpdateContent(actorId, request.content(), Instant.now()));
    } catch (Exception e) {
      return errorFrom(e);
    }
    return HttpResponses.ok(toApi(fetchMemo(memoId)));
  }

  @Patch("/{memoId}/visibility")
  public HttpResponse updateVisibility(String memoId, UpdateVisibilityRequest request) {
    String actorId = viewerId().orElse(null);
    Visibility visibility = parseVisibility(request.visibility());
    if (visibility == null) {
      return badRequest("Unknown visibility: " + request.visibility());
    }
    if (visibility == Visibility.SPACE && actorId != null) {
      HttpResponse spaceCheck = requireActiveMember(request.spaceId(), actorId);
      if (spaceCheck != null) return spaceCheck;
    }
    try {
      componentClient
          .forEventSourcedEntity(memoId)
          .method(MemoEntity::updateVisibility)
          .invoke(new MemoEntity.UpdateVisibility(actorId, visibility, request.spaceId(), Instant.now()));
    } catch (Exception e) {
      return errorFrom(e);
    }
    return HttpResponses.ok(toApi(fetchMemo(memoId)));
  }

  @Patch("/{memoId}/pinned")
  public HttpResponse setPinned(String memoId, SetPinnedRequest request) {
    String actorId = viewerId().orElse(null);
    try {
      componentClient
          .forEventSourcedEntity(memoId)
          .method(MemoEntity::setPinned)
          .invoke(new MemoEntity.SetPinned(actorId, request.pinned(), Instant.now()));
    } catch (Exception e) {
      return errorFrom(e);
    }
    return HttpResponses.ok(toApi(fetchMemo(memoId)));
  }

  @Post("/{memoId}/archive")
  public HttpResponse archive(String memoId) {
    String actorId = viewerId().orElse(null);
    try {
      componentClient
          .forEventSourcedEntity(memoId)
          .method(MemoEntity::archive)
          .invoke(new MemoEntity.ArchiveMemo(actorId, Instant.now()));
    } catch (Exception e) {
      return errorFrom(e);
    }
    return HttpResponses.ok(toApi(fetchMemo(memoId)));
  }

  private Optional<String> viewerId() {
    return requestContext().requestHeader("X-User-Id").map(h -> h.value()).filter(v -> !v.isBlank());
  }

  private ReadDecision decide(Memo memo) {
    return decide(memo, new HashMap<>(), fetchAllowAnonymousAccess());
  }

  private ReadDecision decide(Memo memo, Map<String, Space> spaceCache, boolean allowAnonymous) {
    String viewerId = viewerId().orElse(null);
    boolean isMember = false;
    if (memo.visibility() == Visibility.SPACE && memo.spaceId() != null) {
      Space space = spaceCache.computeIfAbsent(memo.spaceId(), this::fetchSpace);
      isMember = space != null && space.isActiveMember(viewerId);
    }
    return MemoReadAccess.evaluate(memo, viewerId, isMember, allowAnonymous);
  }

  private boolean fetchAllowAnonymousAccess() {
    return componentClient
        .forKeyValueEntity(InstanceSettingsEntity.ENTITY_ID)
        .method(InstanceSettingsEntity::get)
        .invoke()
        .allowAnonymousAccess();
  }

  private HttpResponse requireActiveMember(String spaceId, String userId) {
    Space space = spaceId == null ? null : fetchSpace(spaceId);
    if (space == null || !space.isActiveMember(userId)) {
      // R4 -- a non-member gets NOT_FOUND, not PERMISSION, to avoid leaking space existence.
      return HttpResponses.notFound("Space not found");
    }
    return null;
  }

  private Memo fetchMemo(String memoId) {
    try {
      return componentClient.forEventSourcedEntity(memoId).method(MemoEntity::get).invoke();
    } catch (Exception e) {
      return null;
    }
  }

  private Space fetchSpace(String spaceId) {
    try {
      return componentClient.forEventSourcedEntity(spaceId).method(SpaceEntity::get).invoke();
    } catch (Exception e) {
      return null;
    }
  }

  private static Visibility parseVisibility(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Visibility.valueOf(value.toUpperCase());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private MemoResponse toApi(Memo memo) {
    return new MemoResponse(
        memo.memoId(),
        memo.creatorId(),
        memo.content(),
        memo.visibility().name(),
        memo.spaceId(),
        memo.pinned(),
        memo.tags(),
        memo.archived(),
        memo.createdAt().toString(),
        memo.updatedAt().toString());
  }

  private HttpResponse errorFrom(Exception e) {
    String message = e.getMessage() == null ? "" : e.getMessage();
    if (message.contains("Only the creator")) return forbidden(message);
    if (message.contains("not found")) return HttpResponses.notFound(message);
    return badRequest(message);
  }

  private static HttpResponse badRequest(String message) {
    return HttpResponses.badRequest(message == null ? "Bad request" : message);
  }

  private static HttpResponse unauthenticated(String message) {
    return jsonError(StatusCodes.UNAUTHORIZED, message);
  }

  private static HttpResponse forbidden(String message) {
    return jsonError(StatusCodes.FORBIDDEN, message);
  }

  private static HttpResponse jsonError(akka.http.javadsl.model.StatusCode status, String message) {
    String body = "{\"error\":\"" + message.replace("\"", "'") + "\"}";
    return HttpResponses.of(status, ContentTypes.APPLICATION_JSON, body.getBytes(StandardCharsets.UTF_8));
  }
}
