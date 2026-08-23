package io.akka.usememos.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;

/**
 * SPEC-001 R15 — the single workspace-wide setting the read-access rule depends on. Always
 * addressed with entity id {@code "global"}; there is exactly one instance.
 */
@Component(id = "instance-settings")
public class InstanceSettingsEntity extends KeyValueEntity<InstanceSettingsEntity.State> {

  public static final String ENTITY_ID = "global";

  public record State(boolean allowAnonymousAccess) {
    public static State defaults() {
      return new State(false);
    }
  }

  @Override
  public State emptyState() {
    return State.defaults();
  }

  public Effect<Done> setAllowAnonymousAccess(boolean allow) {
    return effects().updateState(new State(allow)).thenReply(Done.getInstance());
  }

  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }
}
