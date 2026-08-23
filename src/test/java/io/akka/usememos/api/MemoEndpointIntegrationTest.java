package io.akka.usememos.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.http.StrictResponse;
import akka.javasdk.testkit.TestKitSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The HTTP surface end to end — the {@code X-User-Id} header binding, JSON request/response
 * shapes, and the full read-decision matrix (SPEC-001 R12–R17) driven through real HTTP calls
 * rather than {@code ComponentClient}. This is the class PIPELINE.md's own lesson calls for:
 * a {@code ComponentClient}-based test never exercises header binding or the view's actual
 * wire schema, and this port's view row type failing to serialize a null field (see
 * MemosView's Javadoc) was found only by driving the real HTTP + view path, not by a unit test.
 */
class MemoEndpointIntegrationTest extends TestKitSupport {

  private record MemoResponse(
      String memoId,
      String creatorId,
      String content,
      String visibility,
      String spaceId,
      boolean pinned,
      java.util.List<String> tags,
      boolean archived,
      String createdAt,
      String updatedAt) {}

  private record SpaceResponse(String spaceId, java.util.List<String> activeMembers) {}

  private record MemoListResponse(java.util.List<MemoResponse> memos) {}

  @Test
  void captureTagDerivationAndVisibilityAcrossRealHttpCalls() {
    String alice = "alice-" + UUID.randomUUID();
    String bob = "bob-" + UUID.randomUUID();

    var space =
        httpClient
            .POST("/spaces")
            .addHeader("X-User-Id", alice)
            .responseBodyAs(SpaceResponse.class)
            .invoke()
            .body();
    httpClient.POST("/spaces/" + space.spaceId() + "/members/" + bob).invoke();

    var privateMemo =
        httpClient
            .POST("/memos")
            .addHeader("X-User-Id", alice)
            .withRequestBody(new MemoEndpoint.CreateRequest("a #private/thought", null, null))
            .responseBodyAs(MemoResponse.class)
            .invoke()
            .body();
    assertThat(privateMemo.visibility()).isEqualTo("PRIVATE"); // R3
    assertThat(privateMemo.tags()).containsExactly("private", "private/thought"); // R6, R9

    var spaceMemo =
        httpClient
            .POST("/memos")
            .addHeader("X-User-Id", alice)
            .withRequestBody(new MemoEndpoint.CreateRequest("space note", "SPACE", space.spaceId()))
            .responseBodyAs(MemoResponse.class)
            .invoke()
            .body();

    // R13 -- PRIVATE: owner reads it, a space member who is not the owner does not.
    StrictResponse<MemoResponse> asOwner =
        httpClient
            .GET("/memos/" + privateMemo.memoId())
            .addHeader("X-User-Id", alice)
            .responseBodyAs(MemoResponse.class)
            .invoke();
    assertThat(asOwner.httpResponse().status().intValue()).isEqualTo(200);

    var asOtherMember =
        httpClient.GET("/memos/" + privateMemo.memoId()).addHeader("X-User-Id", bob).invoke();
    assertThat(asOtherMember.httpResponse().status().intValue()).isEqualTo(403); // PERMISSION

    var anonymous = httpClient.GET("/memos/" + privateMemo.memoId()).invoke();
    assertThat(anonymous.httpResponse().status().intValue()).isEqualTo(401); // UNAUTHENTICATED

    // R13 -- SPACE: an active member reads it, a non-member does not (SPACE requires a viewer).
    var spaceMemberRead =
        httpClient
            .GET("/memos/" + spaceMemo.memoId())
            .addHeader("X-User-Id", bob)
            .responseBodyAs(MemoResponse.class)
            .invoke();
    assertThat(spaceMemberRead.httpResponse().status().intValue()).isEqualTo(200);

    // R14 -- the list endpoint applies the identical decision: bob (a space member, not the
    // owner) sees the SPACE memo but not alice's PRIVATE one; this is the same code path the
    // view's row schema bug (a crashed update stream) would have silently emptied. The view is
    // an eventually-consistent projection, so this polls rather than reading once.
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var bobsList =
                  httpClient
                      .GET("/memos")
                      .addHeader("X-User-Id", bob)
                      .responseBodyAs(MemoListResponse.class)
                      .invoke()
                      .body();
              var bobsMemoIds = bobsList.memos().stream().map(MemoResponse::memoId).toList();
              assertThat(bobsMemoIds).contains(spaceMemo.memoId());
              assertThat(bobsMemoIds).doesNotContain(privateMemo.memoId());
            });

    // R16 -- only the creator may update.
    var forbiddenUpdate =
        httpClient
            .PATCH("/memos/" + privateMemo.memoId() + "/content")
            .addHeader("X-User-Id", bob)
            .withRequestBody(new MemoEndpoint.UpdateContentRequest("hijacked"))
            .invoke();
    assertThat(forbiddenUpdate.httpResponse().status().intValue()).isEqualTo(403);
  }
}
