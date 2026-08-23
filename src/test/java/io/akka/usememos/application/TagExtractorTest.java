package io.akka.usememos.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R6–R11. Every case here was first run against the real source
 * (usememos-port/probes/tag_probe_test.go, overlaid via {@code go test -overlay}) and the
 * expected value below is the source's own output — see usememos-port/docs/question-log.md
 * rows 3–9.
 */
class TagExtractorTest {

  @Test
  void hierarchyExpandsToEveryAncestorLevel() {
    assertThat(TagExtractor.extract("#book/fiction/history"))
        .containsExactly("book", "book/fiction", "book/fiction/history");
  }

  @Test
  void noLeftBoundaryRequiredBeforeIntroducer() {
    assertThat(TagExtractor.extract("hello#tag")).containsExactly("tag");
  }

  @Test
  void caseIsPreservedAndDistinct() {
    assertThat(TagExtractor.extract("#Work and #work are different"))
        .containsExactly("Work", "work");
  }

  @Test
  void inlineCodeSpanIsExcluded() {
    assertThat(TagExtractor.extract("`#not-a-tag` in code")).isEmpty();
  }

  @Test
  void fencedCodeBlockIsExcluded() {
    assertThat(TagExtractor.extract("```\n#not-a-tag fenced\n```")).isEmpty();
  }

  @Test
  void markdownLinkIsExcludedLabelAndDestination() {
    assertThat(TagExtractor.extract("[release #notes](https://example.com/releases#notes)"))
        .isEmpty();
  }

  @Test
  void apostropheJoinsAContractionButNotATrailingPunctuationMark() {
    assertThat(TagExtractor.extract("#tag's contraction and #O’Brien"))
        .containsExactly("tag's", "O’Brien");
  }

  @Test
  void unsupportedCharacterTerminatesTheIdentifier() {
    assertThat(TagExtractor.extract("#foo,bar stops at comma")).containsExactly("foo");
  }

  @Test
  void connectorCharactersMayStartEndOrFillASegment() {
    assertThat(TagExtractor.extract("#-foo #foo- #--- #C++ #R&D"))
        .containsExactly("-foo", "foo-", "---", "C++", "R&D");
  }

  @Test
  void nestednessIsPreservedInPlainText() {
    assertThat(TagExtractor.extract("plain #a/b/c nested")).containsExactly("a", "a/b", "a/b/c");
  }

  @Test
  void noTagWhenContentHasNoIntroducer() {
    assertThat(TagExtractor.extract("just plain text")).isEmpty();
  }

  @Test
  void escapedIntroducerIsNotATag() {
    assertThat(TagExtractor.extract("\\#tag")).isEmpty();
  }

  @Test
  void trailingSlashIsNotConsumed() {
    assertThat(TagExtractor.extract("#book/")).containsExactly("book");
  }

  @Test
  void duplicateOccurrencesCollapseToOneMembership() {
    assertThat(TagExtractor.extract("#work first, #work again")).containsExactly("work");
  }
}
