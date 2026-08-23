package io.akka.usememos.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import io.akka.usememos.application.SpaceEntity;
import io.akka.usememos.domain.Space;
import java.util.List;
import java.util.UUID;

/** SPEC-001 §2 — just enough of a space to make {@code SPACE} visibility meaningful. */
@HttpEndpoint("/spaces")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class SpaceEndpoint extends AbstractHttpEndpoint {

  public record SpaceResponse(String spaceId, List<String> activeMembers) {}

  private final ComponentClient componentClient;

  public SpaceEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("")
  public HttpResponse create() {
    String creatorId = requestContext().requestHeader("X-User-Id").map(h -> h.value()).orElse(null);
    if (creatorId == null || creatorId.isBlank()) {
      return HttpResponses.badRequest("A creator is required");
    }
    String spaceId = UUID.randomUUID().toString();
    componentClient
        .forEventSourcedEntity(spaceId)
        .method(SpaceEntity::create)
        .invoke(new SpaceEntity.Create(creatorId));
    return HttpResponses.created(toApi(fetch(spaceId)), "/spaces/" + spaceId);
  }

  @Post("/{spaceId}/members/{userId}")
  public HttpResponse addMember(String spaceId, String userId) {
    try {
      componentClient.forEventSourcedEntity(spaceId).method(SpaceEntity::addMember).invoke(userId);
    } catch (Exception e) {
      return HttpResponses.notFound("Space not found");
    }
    return HttpResponses.ok(toApi(fetch(spaceId)));
  }

  @Get("/{spaceId}")
  public HttpResponse get(String spaceId) {
    Space space = fetch(spaceId);
    if (space == null) return HttpResponses.notFound("Space not found");
    return HttpResponses.ok(toApi(space));
  }

  private Space fetch(String spaceId) {
    try {
      return componentClient.forEventSourcedEntity(spaceId).method(SpaceEntity::get).invoke();
    } catch (Exception e) {
      return null;
    }
  }

  private SpaceResponse toApi(Space space) {
    return new SpaceResponse(space.spaceId(), List.copyOf(space.activeMembers()));
  }
}
