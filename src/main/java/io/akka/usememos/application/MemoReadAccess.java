package io.akka.usememos.application;

import io.akka.usememos.domain.Memo;
import io.akka.usememos.domain.ReadDecision;

/**
 * SPEC-001 R12–R15 — the one implementation of the read-access rule (R14), used both for a
 * direct single-memo read and for filtering a memo listing. The source has two independent
 * implementations of this same rule (a Go function and a per-database SQL predicate) that its
 * own comments show already drifted from each other once; this port has exactly one.
 */
public final class MemoReadAccess {

  private MemoReadAccess() {}

  /**
   * @param viewerId {@code null} means an anonymous (not signed-in) reader
   * @param viewerIsSpaceMember whether {@code viewerId} is an active member of {@code
   *     memo.spaceId()} — irrelevant, and ignored, unless {@code memo.visibility() == SPACE}
   * @param allowAnonymousAccess the one instance-wide setting this rule depends on (R15)
   */
  public static ReadDecision evaluate(
      Memo memo, String viewerId, boolean viewerIsSpaceMember, boolean allowAnonymousAccess) {
    if (memo.archived() && !memo.creatorId().equals(viewerId)) {
      return ReadDecision.NOT_FOUND;
    }
    boolean hasViewer = viewerId != null;
    boolean viewerIsCreator = hasViewer && viewerId.equals(memo.creatorId());

    return switch (memo.visibility()) {
      case PRIVATE -> {
        if (!hasViewer) yield ReadDecision.UNAUTHENTICATED;
        yield viewerIsCreator ? ReadDecision.ALLOWED : ReadDecision.PERMISSION;
      }
      case PROTECTED -> hasViewer ? ReadDecision.ALLOWED : ReadDecision.UNAUTHENTICATED;
      case PUBLIC -> {
        if (hasViewer) yield ReadDecision.ALLOWED;
        yield allowAnonymousAccess ? ReadDecision.ALLOWED : ReadDecision.UNAUTHENTICATED;
      }
      case SPACE -> {
        if (!hasViewer) yield ReadDecision.UNAUTHENTICATED;
        yield viewerIsSpaceMember ? ReadDecision.ALLOWED : ReadDecision.PERMISSION;
      }
    };
  }
}
