package io.akka.usememos.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.usememos.application.InstanceSettingsEntity;
import io.akka.usememos.application.MemoReadAccess;
import io.akka.usememos.application.MemosView;
import io.akka.usememos.application.SpaceEntity;
import io.akka.usememos.domain.Memo;
import io.akka.usememos.domain.ReadDecision;
import io.akka.usememos.domain.Space;
import io.akka.usememos.domain.Visibility;
import java.util.HashMap;
import java.util.Map;

/**
 * A minimal server-rendered memo list — RENDERING.md R7: the source's own {@code MemoHeader.tsx}
 * shows a visibility badge (for anything but PRIVATE) and a pinned marker directly on each memo
 * card (`web/src/components/MemoView/components/MemoHeader.tsx:81-96`), which is exactly the
 * state SPEC-001 R12–R17 governs. This page renders the same slice-owned facts in plain markup
 * rather than reproducing the original's design system — see gui/manifest.json.
 *
 * <p>The viewer is a {@code ?as=} query parameter here (there is no browser-native way to set a
 * custom header when simply navigating to a URL) rather than the {@code X-User-Id} header the
 * JSON API under {@code /memos} uses.
 */
@HttpEndpoint("/ui")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class MemosPageEndpoint extends AbstractHttpEndpoint {

  private final ComponentClient componentClient;

  public MemosPageEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/memos")
  public HttpResponse page() {
    String viewerId = requestContext().queryParams().getString("as").orElse(null);
    if (viewerId != null && viewerId.isBlank()) viewerId = null;

    boolean allowAnonymous =
        componentClient
            .forKeyValueEntity(InstanceSettingsEntity.ENTITY_ID)
            .method(InstanceSettingsEntity::get)
            .invoke()
            .allowAnonymousAccess();
    MemosView.MemoRows rows = componentClient.forView().method(MemosView::all).invoke();
    Map<String, Space> spaceCache = new HashMap<>();

    StringBuilder html = new StringBuilder();
    html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>memos</title></head><body>")
        .append("<h1>memos</h1>")
        .append("<p>viewing as: ").append(viewerId == null ? "<em>anonymous</em>" : escape(viewerId))
        .append("</p><ul class=\"memo-list\">");

    for (MemosView.MemoEntry row : rows.memos()) {
      Memo memo =
          new Memo(
              row.memoId(), row.creatorId(), row.content(), row.visibility(),
              row.spaceId().orElse(null), row.pinned(), row.tags(), row.archived(),
              row.createdAt(), row.updatedAt());
      boolean isMember =
          memo.visibility() == Visibility.SPACE
              && memo.spaceId() != null
              && spaceCache
                  .computeIfAbsent(memo.spaceId(), this::fetchSpace)
                  .isActiveMember(viewerId);
      if (MemoReadAccess.evaluate(memo, viewerId, isMember, allowAnonymous) != ReadDecision.ALLOWED) {
        continue;
      }
      html.append("<li class=\"memo-card\">")
          .append("<span class=\"memo-content\">").append(escape(memo.content())).append("</span>");
      if (memo.visibility() != Visibility.PRIVATE) {
        html.append(" <span class=\"memo-visibility\">[").append(memo.visibility()).append("]</span>");
      }
      if (memo.pinned()) {
        html.append(" <span class=\"memo-pinned\">📌</span>");
      }
      if (!memo.tags().isEmpty()) {
        html.append(" <span class=\"memo-tags\">");
        for (String tag : memo.tags()) {
          html.append("#").append(escape(tag)).append(" ");
        }
        html.append("</span>");
      }
      html.append("</li>");
    }
    html.append("</ul></body></html>");

    return HttpResponse.create()
        .withStatus(StatusCodes.OK)
        .withEntity(ContentTypes.TEXT_HTML_UTF8, html.toString());
  }

  private Space fetchSpace(String spaceId) {
    try {
      return componentClient.forEventSourcedEntity(spaceId).method(SpaceEntity::get).invoke();
    } catch (Exception e) {
      return Space.empty();
    }
  }

  private static String escape(String s) {
    return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
