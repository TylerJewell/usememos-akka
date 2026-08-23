package io.akka.usememos.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SPEC-001 R6–R11 — derives a memo's tag set from its Markdown content. A deliberately narrowed
 * subset of the source's ADR 0001 grammar: Unicode letters and digits stand in for the full
 * {@code XID_Continue} table, and fully-qualified emoji sequences are not matched as tag
 * members — see SPEC-001 OD-1. The structural rules (introducer, hierarchy, connectors, the
 * contextual apostrophe joiner, and Markdown-context exclusion) are ported faithfully and were
 * each driven directly against the source before being written here (question-log rows 3–9).
 */
public final class TagExtractor {

  private TagExtractor() {}

  public static List<String> extract(String content) {
    boolean[] excluded = excludedPositions(content);
    Set<String> ordered = new LinkedHashSet<>();

    int i = 0;
    int n = content.length();
    while (i < n) {
      char c = content.charAt(i);
      if (c == '#' && !excluded[i] && !(i > 0 && content.charAt(i - 1) == '\\')) {
        String identifier = scanIdentifier(content, excluded, i + 1);
        if (identifier != null) {
          for (String tag : hierarchy(identifier)) {
            ordered.add(tag);
          }
          i += 1 + identifier.length();
          continue;
        }
      }
      i++;
    }
    return List.copyOf(ordered);
  }

  /** R9 — a hierarchical direct value contributes every non-empty ancestor prefix too. */
  private static List<String> hierarchy(String identifier) {
    List<String> tags = new ArrayList<>();
    int offset = 0;
    while (true) {
      int slash = identifier.indexOf('/', offset);
      if (slash < 0) {
        tags.add(identifier);
        return tags;
      }
      offset = slash + 1;
      tags.add(identifier.substring(0, slash));
    }
  }

  /** R7–R9 — scans one (possibly hierarchical) tag source spelling starting at {@code start}. */
  private static String scanIdentifier(String content, boolean[] excluded, int start) {
    StringBuilder id = new StringBuilder();
    int i = start;
    int n = content.length();
    while (true) {
      int segStart = i;
      if (i < n && isStarter(content, excluded, i)) {
        id.append(content.charAt(i));
        i++;
        // Consume continuation characters, including an apostrophe joiner (R7).
        while (i < n && isContinuation(content, excluded, i, id)) {
          id.append(content.charAt(i));
          i++;
        }
      }
      if (i == segStart) {
        // This segment failed entirely -> keep whatever valid prefix was already consumed.
        return id.length() == 0 ? null : id.toString();
      }
      // R9 — consume '/' only when a valid next segment follows (maximal-prefix rule).
      if (i < n && content.charAt(i) == '/' && i + 1 < n && isStarter(content, excluded, i + 1)) {
        id.append('/');
        i++;
        continue;
      }
      return id.toString();
    }
  }

  private static boolean isStarter(String content, boolean[] excluded, int i) {
    if (excluded[i]) return false;
    char c = content.charAt(i);
    return Character.isLetter(c) || Character.isDigit(c) || c == '-' || c == '+' || c == '&';
  }

  private static boolean isContinuation(
      String content, boolean[] excluded, int i, StringBuilder soFar) {
    if (excluded[i]) return false;
    char c = content.charAt(i);
    if (Character.isLetter(c) || Character.isDigit(c) || c == '-' || c == '+' || c == '&') {
      return true;
    }
    if (c == '\'' || c == '’') {
      char prev = soFar.charAt(soFar.length() - 1);
      boolean prevOk = Character.isLetter(prev) || Character.isDigit(prev);
      boolean nextOk =
          i + 1 < content.length()
              && !excluded[i + 1]
              && (Character.isLetter(content.charAt(i + 1)) || Character.isDigit(content.charAt(i + 1)));
      return prevOk && nextOk;
    }
    return false;
  }

  /**
   * R10 — marks positions inside a fenced code block, an inline code span, or a Markdown
   * link/image label+destination as ineligible for tag recognition. Fenced blocks are detected
   * line by line; inline code spans and links are detected per line (a deliberate simplification
   * of the source's full block-structure-aware parser — SPEC-001 OD-1).
   */
  private static boolean[] excludedPositions(String content) {
    boolean[] excluded = new boolean[content.length()];
    markFencedBlocks(content, excluded);
    int lineStart = 0;
    for (int i = 0; i <= content.length(); i++) {
      if (i == content.length() || content.charAt(i) == '\n') {
        markInlineCodeSpans(content, excluded, lineStart, i);
        markLinksAndImages(content, excluded, lineStart, i);
        lineStart = i + 1;
      }
    }
    return excluded;
  }

  private static void markFencedBlocks(String content, boolean[] excluded) {
    boolean inFence = false;
    char fenceChar = 0;
    int fenceLen = 0;
    int lineStart = 0;
    for (int i = 0; i <= content.length(); i++) {
      if (i == content.length() || content.charAt(i) == '\n') {
        String line = content.substring(lineStart, i);
        String trimmed = line.strip();
        if (!inFence && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
          inFence = true;
          fenceChar = trimmed.charAt(0);
          fenceLen = countLeading(trimmed, fenceChar);
          markRange(excluded, lineStart, i);
        } else if (inFence
            && trimmed.length() >= fenceLen
            && countLeading(trimmed, fenceChar) >= fenceLen
            && isAllChar(trimmed, fenceChar)) {
          markRange(excluded, lineStart, i);
          inFence = false;
        } else if (inFence) {
          markRange(excluded, lineStart, i);
        }
        lineStart = i + 1;
      }
    }
  }

  private static boolean isAllChar(String s, char c) {
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) != c) return false;
    }
    return true;
  }

  private static int countLeading(String s, char c) {
    int n = 0;
    while (n < s.length() && s.charAt(n) == c) n++;
    return n;
  }

  private static void markRange(boolean[] excluded, int from, int to) {
    for (int i = from; i < to && i < excluded.length; i++) excluded[i] = true;
  }

  private static void markInlineCodeSpans(
      String content, boolean[] excluded, int lineStart, int lineEnd) {
    int i = lineStart;
    while (i < lineEnd) {
      if (excluded[i] || content.charAt(i) != '`') {
        i++;
        continue;
      }
      int openStart = i;
      int openLen = countLeading(content.substring(i, lineEnd), '`');
      int afterOpen = i + openLen;
      int j = afterOpen;
      int closeStart = -1;
      while (j < lineEnd) {
        if (content.charAt(j) == '`') {
          int runLen = countLeading(content.substring(j, lineEnd), '`');
          if (runLen == openLen) {
            closeStart = j;
            break;
          }
          j += runLen;
        } else {
          j++;
        }
      }
      if (closeStart >= 0) {
        markRange(excluded, openStart, closeStart + openLen);
        i = closeStart + openLen;
      } else {
        i = afterOpen;
      }
    }
  }

  private static void markLinksAndImages(
      String content, boolean[] excluded, int lineStart, int lineEnd) {
    int i = lineStart;
    while (i < lineEnd) {
      if (!excluded[i] && content.charAt(i) == '[') {
        int labelStart = i;
        int closeBracket = content.indexOf(']', i + 1);
        if (closeBracket > 0 && closeBracket < lineEnd) {
          int destOpen = closeBracket + 1;
          if (destOpen < lineEnd && content.charAt(destOpen) == '(') {
            int closeParen = content.indexOf(')', destOpen + 1);
            if (closeParen > 0 && closeParen < lineEnd) {
              int start = (labelStart > lineStart && content.charAt(labelStart - 1) == '!')
                  ? labelStart - 1
                  : labelStart;
              markRange(excluded, start, closeParen + 1);
              i = closeParen + 1;
              continue;
            }
          }
        }
      }
      i++;
    }
  }
}
