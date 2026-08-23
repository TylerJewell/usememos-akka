package io.akka.usememos.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.usememos.domain.Memo;
import io.akka.usememos.domain.ReadDecision;
import io.akka.usememos.domain.Visibility;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R12–R15. Every case here was first run against the real source
 * (usememos-port/probes/access_probe_test.go, overlaid via {@code go test -overlay}, driving
 * {@code CheckMemoReadContext} directly) — see usememos-port/docs/question-log.md row 11. This
 * port has no share-token parameter at all (SPEC-001 OD-2), so the source's
 * "share-applies-private-anon" and "share-does-not-apply-to-space" cases have no equivalent
 * here; every other branch is reproduced.
 */
class MemoReadAccessTest {

  private static final String OWNER = "owner";
  private static final String OTHER = "other";

  private static Memo memo(Visibility visibility, boolean archived, String spaceId) {
    return new Memo(
        "m1", OWNER, "content", visibility, spaceId, false, List.of(), archived,
        Instant.EPOCH, Instant.EPOCH);
  }

  @Test
  void archivedIsVisibleOnlyToItsCreator() {
    Memo archived = memo(Visibility.PUBLIC, true, null);
    assertThat(MemoReadAccess.evaluate(archived, OTHER, false, true))
        .isEqualTo(ReadDecision.NOT_FOUND);
    assertThat(MemoReadAccess.evaluate(archived, OWNER, false, true))
        .isEqualTo(ReadDecision.ALLOWED);
  }

  @Test
  void publicAllowsAnonymousOnlyWhenInstancePermits() {
    Memo memo = memo(Visibility.PUBLIC, false, null);
    assertThat(MemoReadAccess.evaluate(memo, null, false, true)).isEqualTo(ReadDecision.ALLOWED);
    assertThat(MemoReadAccess.evaluate(memo, null, false, false))
        .isEqualTo(ReadDecision.UNAUTHENTICATED);
    assertThat(MemoReadAccess.evaluate(memo, OTHER, false, false))
        .isEqualTo(ReadDecision.ALLOWED);
  }

  @Test
  void protectedAllowsAnySignedInViewer() {
    Memo memo = memo(Visibility.PROTECTED, false, null);
    assertThat(MemoReadAccess.evaluate(memo, OTHER, false, false)).isEqualTo(ReadDecision.ALLOWED);
    assertThat(MemoReadAccess.evaluate(memo, null, false, false))
        .isEqualTo(ReadDecision.UNAUTHENTICATED);
  }

  @Test
  void privateAllowsOnlyItsCreator() {
    Memo memo = memo(Visibility.PRIVATE, false, null);
    assertThat(MemoReadAccess.evaluate(memo, null, false, false))
        .isEqualTo(ReadDecision.UNAUTHENTICATED);
    assertThat(MemoReadAccess.evaluate(memo, OTHER, false, false))
        .isEqualTo(ReadDecision.PERMISSION);
    assertThat(MemoReadAccess.evaluate(memo, OWNER, false, false))
        .isEqualTo(ReadDecision.ALLOWED);
  }

  @Test
  void spaceAllowsOnlyActiveMembers() {
    Memo memo = memo(Visibility.SPACE, false, "space1");
    assertThat(MemoReadAccess.evaluate(memo, null, false, false))
        .isEqualTo(ReadDecision.UNAUTHENTICATED);
    assertThat(MemoReadAccess.evaluate(memo, OTHER, true, false))
        .isEqualTo(ReadDecision.ALLOWED);
    assertThat(MemoReadAccess.evaluate(memo, OTHER, false, false))
        .isEqualTo(ReadDecision.PERMISSION);
  }

  @Test
  void noAdministratorBypassExists() {
    // MemoReadAccess.evaluate has no role parameter at all -- an "admin" viewer is
    // indistinguishable from any other non-owner viewer, by construction (SPEC-001 row 13).
    Memo memo = memo(Visibility.PRIVATE, false, null);
    assertThat(MemoReadAccess.evaluate(memo, "admin", false, false))
        .isEqualTo(ReadDecision.PERMISSION);
  }

  @Test
  void anonymousAccessSettingHasNoEffectOnSignedInReadersOrOtherVisibilities() {
    // R15 -- allowAnonymousAccess only ever changes the PUBLIC/no-viewer branch.
    assertThat(MemoReadAccess.evaluate(memo(Visibility.PROTECTED, false, null), OTHER, false, false))
        .isEqualTo(MemoReadAccess.evaluate(memo(Visibility.PROTECTED, false, null), OTHER, false, true));
    assertThat(MemoReadAccess.evaluate(memo(Visibility.PRIVATE, false, null), OWNER, false, false))
        .isEqualTo(MemoReadAccess.evaluate(memo(Visibility.PRIVATE, false, null), OWNER, false, true));
  }
}
