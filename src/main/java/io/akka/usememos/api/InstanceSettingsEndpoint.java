package io.akka.usememos.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import io.akka.usememos.application.InstanceSettingsEntity;

/** SPEC-001 R15 — the one workspace-wide setting the read-access rule depends on. */
@HttpEndpoint("/instance-settings")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class InstanceSettingsEndpoint {

  public record SettingsRequest(boolean allowAnonymousAccess) {}

  public record SettingsResponse(boolean allowAnonymousAccess) {}

  private final ComponentClient componentClient;

  public InstanceSettingsEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("")
  public SettingsResponse get() {
    var state =
        componentClient
            .forKeyValueEntity(InstanceSettingsEntity.ENTITY_ID)
            .method(InstanceSettingsEntity::get)
            .invoke();
    return new SettingsResponse(state.allowAnonymousAccess());
  }

  @Put("")
  public SettingsResponse set(SettingsRequest request) {
    componentClient
        .forKeyValueEntity(InstanceSettingsEntity.ENTITY_ID)
        .method(InstanceSettingsEntity::setAllowAnonymousAccess)
        .invoke(request.allowAnonymousAccess());
    return new SettingsResponse(request.allowAnonymousAccess());
  }
}
