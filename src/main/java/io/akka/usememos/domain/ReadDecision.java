package io.akka.usememos.domain;

/** SPEC-001 R13 — the outcome of {@code MemoReadAccess.evaluate}, kept as three distinct
 * denial reasons because the source keeps them distinct: {@code NOT_FOUND} hides existence,
 * {@code UNAUTHENTICATED} means a viewer is required, {@code PERMISSION} means the wrong
 * viewer was given. */
public enum ReadDecision {
  ALLOWED,
  NOT_FOUND,
  UNAUTHENTICATED,
  PERMISSION
}
