/*
 * Copyright 2020 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.api.server;

import static io.confluent.ksql.rest.Errors.toErrorCode;
import static org.apache.http.HttpHeaders.TRANSFER_ENCODING;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import io.confluent.ksql.api.auth.ApiSecurityContext;
import io.confluent.ksql.api.auth.DefaultApiSecurityContext;
import io.confluent.ksql.api.spi.EndpointResponse;
import io.confluent.ksql.api.spi.Endpoints;
import io.confluent.ksql.api.spi.StreamedEndpointResponse;
import io.confluent.ksql.rest.ApiJsonMapper;
import io.confluent.ksql.rest.entity.ClusterTerminateRequest;
import io.confluent.ksql.rest.entity.KsqlErrorMessage;
import io.confluent.ksql.rest.entity.KsqlRequest;
import io.confluent.ksql.rest.entity.Versions;
import io.confluent.ksql.util.VertxCompletableFuture;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import javax.ws.rs.core.MediaType;
import org.apache.http.HttpStatus;

class PortedEndpoints {

  private static final Set<String> PORTED_ENDPOINTS = ImmutableSet
      .of("/ksql", "/ksql/terminate", "/query", "/info");

  private static final String CONTENT_TYPE_HEADER = HttpHeaders.CONTENT_TYPE.toString();
  private static final String JSON_CONTENT_TYPE = "application/json";

  private static final ObjectMapper OBJECT_MAPPER = ApiJsonMapper.INSTANCE.get();

  private static final String CHUNKED_ENCODING = "chunked";

  private final Endpoints endpoints;
  private final Server server;

  PortedEndpoints(final Endpoints endpoints, final Server server) {
    this.endpoints = endpoints;
    this.server = server;
  }

  static void setupEndpoints(final Endpoints endpoints, final Server server,
      final Router router) {
    router.route(HttpMethod.POST, "/ksql")
        .handler(BodyHandler.create())
        .produces(Versions.KSQL_V1_JSON)
        .produces(MediaType.APPLICATION_JSON)
        .handler(new PortedEndpoints(endpoints, server)::handleKsqlRequest);
    router.route(HttpMethod.POST, "/ksql/terminate")
        .handler(BodyHandler.create())
        .produces(Versions.KSQL_V1_JSON)
        .produces(MediaType.APPLICATION_JSON)
        .handler(new PortedEndpoints(endpoints, server)::handleTerminateRequest);
    router.route(HttpMethod.POST, "/query")
        .handler(BodyHandler.create())
        .produces(Versions.KSQL_V1_JSON)
        .produces(MediaType.APPLICATION_JSON)
        .handler(new PortedEndpoints(endpoints, server)::handleQueryRequest);
    router.route(HttpMethod.GET, "/info")
        .produces(Versions.KSQL_V1_JSON)
        .produces(MediaType.APPLICATION_JSON)
        .handler(new PortedEndpoints(endpoints, server)::handleInfoRequest);
  }

  static void setupFailureHandler(final Router router) {
    for (String path : PORTED_ENDPOINTS) {
      router.route(path).failureHandler(PortedEndpoints::oldApiFailureHandler);
    }
  }

  void handleKsqlRequest(final RoutingContext routingContext) {
    handlePortedOldApiRequest(server, routingContext, KsqlRequest.class,
        (ksqlRequest, apiSecurityContext) ->
            endpoints
                .executeKsqlRequest(ksqlRequest, server.getWorkerExecutor(),
                    DefaultApiSecurityContext.create(routingContext))
    );
  }

  void handleTerminateRequest(final RoutingContext routingContext) {
    handlePortedOldApiRequest(server, routingContext, ClusterTerminateRequest.class,
        (request, apiSecurityContext) ->
            endpoints
                .executeTerminate(request, server.getWorkerExecutor(),
                    DefaultApiSecurityContext.create(routingContext))
    );
  }

  void handleQueryRequest(final RoutingContext routingContext) {

    final CompletableFuture<Void> connectionClosedFuture = new CompletableFuture<>();
    routingContext.request().connection().closeHandler(v -> connectionClosedFuture.complete(null));

    handlePortedOldApiRequest(server, routingContext, KsqlRequest.class,
        (request, apiSecurityContext) ->
            endpoints
                .executeQueryRequest(request, server.getWorkerExecutor(), connectionClosedFuture,
                    DefaultApiSecurityContext.create(routingContext))
    );
  }

  void handleInfoRequest(final RoutingContext routingContext) {
    handlePortedOldApiRequest(server, routingContext, null,
        (request, apiSecurityContext) ->
            endpoints.executeInfo(DefaultApiSecurityContext.create(routingContext))
    );
  }

  private static <T> void handlePortedOldApiRequest(final Server server,
      final RoutingContext routingContext,
      final Class<T> requestClass,
      final BiFunction<T, ApiSecurityContext, CompletableFuture<EndpointResponse>> requestor) {
    final HttpServerResponse response = routingContext.response();
    final T requestObject;
    if (requestClass != null) {
      try {
        requestObject = OBJECT_MAPPER.readValue(routingContext.getBody().getBytes(), requestClass);
      } catch (Exception e) {
        routingContext.fail(HttpStatus.SC_BAD_REQUEST,
            new KsqlApiException("Malformed JSON", ErrorCodes.ERROR_CODE_MALFORMED_REQUEST));
        return;
      }
    } else {
      requestObject = null;
    }
    final CompletableFuture<EndpointResponse> completableFuture = requestor
        .apply(requestObject, DefaultApiSecurityContext.create(routingContext));
    completableFuture.thenAccept(endpointResponse -> {

      response.putHeader(CONTENT_TYPE_HEADER, JSON_CONTENT_TYPE);

      response.setStatusCode(endpointResponse.getStatusCode())
          .setStatusMessage(endpointResponse.getStatusMessage());

      // What the old API returns in it's response is something of a mishmash - sometimes it's
      // a plain String, other times it's an object that needs to be JSON encoded, other times
      // it represents a stream.
      if (endpointResponse instanceof StreamedEndpointResponse) {
        if (routingContext.request().version() == HttpVersion.HTTP_2) {
          // The old /query endpoint uses chunked encoding which is not supported in HTTP2
          routingContext.response().setStatusCode(HttpStatus.SC_METHOD_NOT_ALLOWED)
              .setStatusMessage("The /query endpoint is not available using HTTP2").end();
          return;
        }
        response.putHeader(TRANSFER_ENCODING, CHUNKED_ENCODING);
        streamEndpointResponse(server, response, (StreamedEndpointResponse) endpointResponse);
      } else {
        final Buffer responseBody;
        if (endpointResponse.getResponseBody() instanceof String) {
          responseBody = Buffer.buffer((String) endpointResponse.getResponseBody());
        } else {
          try {
            final byte[] bytes = OBJECT_MAPPER
                .writeValueAsBytes(endpointResponse.getResponseBody());
            responseBody = Buffer.buffer(bytes);
          } catch (JsonProcessingException e) {
            // This is an internal error as it's a bug in the server
            routingContext.fail(HttpStatus.SC_INTERNAL_SERVER_ERROR, e);
            return;
          }
        }
        response.end(responseBody);
      }
    }).exceptionally(t -> {
      routingContext.fail(HttpStatus.SC_INTERNAL_SERVER_ERROR, t);
      return null;
    });
  }

  private static void streamEndpointResponse(final Server server, final HttpServerResponse response,
      final StreamedEndpointResponse streamedEndpointResponse) {
    final WorkerExecutor workerExecutor = server.getWorkerExecutor();
    final VertxCompletableFuture<Void> vcf = new VertxCompletableFuture<>();
    workerExecutor.executeBlocking(promise -> {
      try (OutputStream os = new BufferedOutputStream(new ResponseOutputStream(response))) {
        streamedEndpointResponse.execute(os);
      } catch (Exception e) {
        promise.fail(e);
      }
    }, vcf);
  }

  private static void oldApiFailureHandler(final RoutingContext routingContext) {
    final int statusCode = routingContext.statusCode();

    final KsqlErrorMessage ksqlErrorMessage;
    if (routingContext.failure() instanceof KsqlApiException) {
      final KsqlApiException ksqlApiException = (KsqlApiException) routingContext.failure();
      ksqlErrorMessage = new KsqlErrorMessage(
          ksqlApiException.getErrorCode(),
          ksqlApiException.getMessage());
    } else {
      ksqlErrorMessage = new KsqlErrorMessage(
          toErrorCode(statusCode),
          routingContext.failure().getMessage());
    }
    try {
      final byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(ksqlErrorMessage);
      routingContext.response().setStatusCode(statusCode)
          .end(Buffer.buffer(bytes));
    } catch (JsonProcessingException e) {
      routingContext.fail(HttpStatus.SC_INTERNAL_SERVER_ERROR, e);
    }
  }

}