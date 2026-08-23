package io.akka.usememos.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.usememos.domain.Space;
import java.util.HashSet;
import java.util.Set;

/**
 * SPEC-001 §2 — enough of a space to make {@code SPACE} visibility meaningful (R4, R13):
 * whether it exists, and who is currently an active member.
 */
@Component(id = "space")
public class SpaceEntity extends EventSourcedEntity<Space, SpaceEntity.Event> {

  public sealed interface Event {}

  @TypeName("space-created")
  public record SpaceCreated(String spaceId, String creatorId) implements Event {}

  @TypeName("space-member-added")
  public record MemberAdded(String userId) implements Event {}

  @TypeName("space-member-removed")
  public record MemberRemoved(String userId) implements Event {}

  public record Create(String creatorId) {}

  @Override
  public Space emptyState() {
    return Space.empty();
  }

  public Effect<Done> create(Create command) {
    if (currentState().exists()) {
      return effects().error("Space already exists");
    }
    if (command.creatorId() == null || command.creatorId().isBlank()) {
      return effects().error("A space requires a creator");
    }
    var created = new SpaceCreated(commandContext().entityId(), command.creatorId());
    return effects().persist(created).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> addMember(String userId) {
    if (!currentState().exists()) {
      return effects().error("Space not found");
    }
    if (userId == null || userId.isBlank()) {
      return effects().error("A member id is required");
    }
    return effects().persist(new MemberAdded(userId)).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> removeMember(String userId) {
    if (!currentState().exists()) {
      return effects().error("Space not found");
    }
    return effects().persist(new MemberRemoved(userId)).thenReply(s -> Done.getInstance());
  }

  public ReadOnlyEffect<Space> get() {
    if (!currentState().exists()) {
      return effects().error("Space not found");
    }
    return effects().reply(currentState());
  }

  @Override
  public Space applyEvent(Event event) {
    return switch (event) {
      case SpaceCreated e -> new Space(e.spaceId(), Set.of(e.creatorId()));
      case MemberAdded e -> {
        Set<String> members = new HashSet<>(currentState().activeMembers());
        members.add(e.userId());
        yield new Space(currentState().spaceId(), Set.copyOf(members));
      }
      case MemberRemoved e -> {
        Set<String> members = new HashSet<>(currentState().activeMembers());
        members.remove(e.userId());
        yield new Space(currentState().spaceId(), Set.copyOf(members));
      }
    };
  }
}
