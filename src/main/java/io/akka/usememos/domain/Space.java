package io.akka.usememos.domain;

import java.util.Set;

/**
 * SPEC-001 §2 — the minimal shape a {@code SPACE}-visibility memo needs: does the space exist,
 * and who is currently an active member. Not the source's full space model (title, roles,
 * administration) — see SPEC-001 §1 out-of-scope.
 */
public record Space(String spaceId, Set<String> activeMembers) {

  public static Space empty() {
    return new Space(null, Set.of());
  }

  public boolean exists() {
    return spaceId != null;
  }

  public boolean isActiveMember(String userId) {
    return userId != null && activeMembers.contains(userId);
  }
}
