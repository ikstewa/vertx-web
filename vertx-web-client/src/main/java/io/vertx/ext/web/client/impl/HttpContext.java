/*
 * Copyright 2014 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */
package io.vertx.ext.web.client.impl;

import io.netty.handler.codec.http.QueryStringEncoder;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.impl.HttpClientImpl;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.streams.Pipe;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.codec.spi.BodyStream;
import io.vertx.ext.web.multipart.MultipartForm;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class HttpContext<T> {

  private final Handler<AsyncResult<HttpResponse<T>>> handler;
  private final HttpClientImpl client;
  private final List<Handler<HttpContext<?>>> interceptors;
  private Context context;
  private HttpRequestImpl<T> request;
  private Object body;
  private String contentType;
  private Map<String, Object> attrs;
  private int interceptorIdx;
  private boolean invoking;
  private boolean invokeNext;
  private ClientPhase phase;
  private RequestOptions requestOptions;
  private HttpClientRequest clientRequest;
  private HttpClientResponse clientResponse;
  private Promise<HttpClientRequest> requestPromise;
  private HttpResponse<T> response;
  private Throwable failure;
  private int redirects;
  private List<String> redirectedLocations = Collections.emptyList();

  HttpContext(HttpClientImpl client, List<Handler<HttpContext<?>>> interceptors, Handler<AsyncResult<HttpResponse<T>>> handler) {
    this.handler = handler;
    this.client = client;
    this.interceptors = interceptors;
  }

  /**
   * @return the underlying client request, only available during {@link ClientPhase#SEND_REQUEST} and after
   */
  public HttpClientRequest clientRequest() {
    return clientRequest;
  }

  /**
   * @return the underlying client request, only available during {@link ClientPhase#RECEIVE_RESPONSE} and after
   */
  public HttpClientResponse clientResponse() {
    return clientResponse;
  }

  /**
   * @return the current event type
   */
  public ClientPhase phase() {
    return phase;
  }

  /**
   * @return the current request object
   */
  public HttpRequest<T> request() {
    return request;
  }

  /**
   * @return the current request options
   */
  public RequestOptions requestOptions() {
    return requestOptions;
  }

  public void setRequestOptions(RequestOptions requestOptions) {
    this.requestOptions = requestOptions;
  }

  /**
   * @return the current response object, only available during {@link ClientPhase#DISPATCH_RESPONSE}
   */
  public HttpResponse<T> response() {
    return response;
  }

  public HttpContext<T> response(HttpResponse<T> response) {
    this.response = response;
    return this;
  }

  /**
   * @return the number of followed redirects, this value is initialized to {@code 0} during the prepare phase
   */
  public int redirects() {
    return redirects;
  }

  /**
   * Set the number of followed redirects.
   *
   * @param redirects the new value
   * @return a reference to this, so the API can be used fluently
   */
  public HttpContext<T> redirects(int redirects) {
    this.redirects = redirects;
    return this;
  }

  /**
   * @return the request content type
   */
  public String contentType() {
    return contentType;
  }

  /**
   * @return the body to send
   */
  public Object body() {
    return body;
  }

  /**
   * @return the failure, only for {@link ClientPhase#FAILURE}
   */
  public Throwable failure() {
    return failure;
  }

  /**
   * @return all traced redirects
   */
  public List<String> getRedirectedLocations() {
    return redirectedLocations;
  }

  /**
   * Prepare the HTTP request, this executes the {@link ClientPhase#PREPARE_REQUEST} phase:
   * <ul>
   *   <li>Traverse the interceptor chain</li>
   *   <li>Execute the {@link ClientPhase#CREATE_REQUEST} phase</li>
   * </ul>
   */
  public void prepareRequest(HttpRequest<T> request, String contentType, Object body) {
    this.request = (HttpRequestImpl<T>) request;
    this.contentType = contentType;
    this.body = body;
    fire(ClientPhase.PREPARE_REQUEST);
  }

  /**
   * Create the HTTP request, this executes the {@link ClientPhase#CREATE_REQUEST} phase:
   * <ul>
   *   <li>Traverse the interceptor chain</li>
   *   <li>Create the {@link HttpClientRequest}</li>
   * </ul>
   */
  public void createRequest(RequestOptions requestOptions) {
    this.requestOptions = requestOptions;
    fire(ClientPhase.CREATE_REQUEST);
  }

  /**
   * Send the HTTP request, this executes the {@link ClientPhase#SEND_REQUEST} phase:
   * <ul>
   *   <li>Traverse the interceptor chain</li>
   *   <li>Send the actual request</li>
   * </ul>
   */
  public void sendRequest(HttpClientRequest clientRequest) {
    this.clientRequest = clientRequest;
    fire(ClientPhase.SEND_REQUEST);
  }

  /**
   * Follow the redirect, this executes the {@link ClientPhase#FOLLOW_REDIRECT} phase:
   * <ul>
   *   <li>Traverse the interceptor chain</li>
   *   <li>Send the redirect request</li>
   * </ul>
   */
  public void followRedirect() {
    fire(ClientPhase.CREATE_REQUEST);
  }

  /**
   * Receive the HTTP response, this executes the {@link ClientPhase#RECEIVE_RESPONSE} phase:
   * <ul>
   *   <li>Traverse the interceptor chain</li>
   *   <li>Execute the {@link ClientPhase#DISPATCH_RESPONSE} phase</li>
   * </ul>
   */
  public void receiveResponse(HttpClientResponse clientResponse) {
    int sc = clientResponse.statusCode();
    int maxRedirects = request.followRedirects ? client.getOptions().getMaxRedirects(): 0;
    this.clientResponse = clientResponse;
    if (redirects < maxRedirects && sc >= 300 && sc < 400) {
      redirects++;
      Future<RequestOptions> next = client.redirectHandler().apply(clientResponse);
      if (next != null) {
        if (redirectedLocations.isEmpty()) {
          redirectedLocations = new ArrayList<>();
        }
        redirectedLocations.add(clientResponse.getHeader(HttpHeaders.LOCATION));
        next.onComplete(ar -> {
          if (ar.succeeded()) {
            requestOptions = ar.result();
            fire(ClientPhase.FOLLOW_REDIRECT);
          } else {
            fail(ar.cause());
          }
        });
        return;
      }
    }
    this.clientResponse = clientResponse;
    fire(ClientPhase.RECEIVE_RESPONSE);
  }

  /**
   * Dispatch the HTTP response, this executes the {@link ClientPhase#DISPATCH_RESPONSE} phase:
   * <ul>
   *   <li>Create the {@link HttpResponse}</li>
   *   <li>Traverse the interceptor chain</li>
   *   <li>Deliver the response to the response handler</li>
   * </ul>
   */
  public void dispatchResponse(HttpResponse<T> response) {
    this.response = response;
    fire(ClientPhase.DISPATCH_RESPONSE);
  }

  /**
   * Fail the current HTTP context, this executes the {@link ClientPhase#FAILURE} phase:
   * <ul>
   *   <li>Traverse the interceptor chain</li>
   *   <li>Deliver the failure to the response handler</li>
   * </ul>
   *
   * @param cause the failure cause
   * @return {@code true} if the failure can be dispatched
   */
  public boolean fail(Throwable cause) {
    if (phase == ClientPhase.FAILURE) {
      // Already processing a failure
      return false;
    }
    failure = cause;
    fire(ClientPhase.FAILURE);
    return true;
  }

  /**
   * Fire a client execution phase.
   *
   * When an interception phase is in progress, the current phase is interrupted and
   * a new interception phase begins.
   *
   * @param phase the phase to execute
   */
  private void fire(ClientPhase phase) {
    Objects.requireNonNull(phase);
    this.phase = phase;
    if (invoking) {
      this.interceptorIdx = 0;
      this.invokeNext = true;
    } else {
      next();
    }
  }

  /**
   * Call the next interceptor in the chain.
   */
  public void next() {
    if (invoking) {
      invokeNext = true;
    } else {
      while (interceptorIdx < interceptors.size()) {
        Handler<HttpContext<?>> interceptor = interceptors.get(interceptorIdx);
        invoking = true;
        interceptorIdx++;
        try {
          interceptor.handle(this);
        } catch (Exception e ) {
          // Internal failure => directly dispatch a failure without the interceptor stack
          // that could lead to infinite failures
          failure = e;
          invokeNext = false;
          phase = ClientPhase.FAILURE;
          break;
        } finally {
          invoking = false;
        }
        if (!invokeNext) {
          return;
        }
        invokeNext = false;
      }
      interceptorIdx = 0;
      execute();
    }
  }

  private void execute() {
    switch (phase) {
      case PREPARE_REQUEST:
        handlePrepareRequest();
        break;
      case CREATE_REQUEST:
        handleCreateRequest();
        break;
      case SEND_REQUEST:
        handleSendRequest();
        break;
      case FOLLOW_REDIRECT:
        followRedirect();
        break;
      case RECEIVE_RESPONSE:
        handleReceiveResponse();
        break;
      case DISPATCH_RESPONSE:
        handleDispatchResponse();
        break;
      case FAILURE:
        handleFailure();
        break;
    }
  }

  private void handleFailure() {
    handler.handle(Future.failedFuture(failure));
  }

  private void handleDispatchResponse() {
    handler.handle(Future.succeededFuture(response));
  }

  private void handlePrepareRequest() {
    context = client.getVertx().getOrCreateContext();
    String requestURI;
    if (request.params != null && request.params.size() > 0) {
      QueryStringEncoder enc = new QueryStringEncoder(request.uri);
      request.params.forEach(param -> enc.addParam(param.getKey(), param.getValue()));
      requestURI = enc.toString();
    } else {
      requestURI = request.uri;
    }
    int port = request.port();
    String host = request.host();
    RequestOptions options = new RequestOptions();
    if (request.ssl != null && request.ssl != request.options.isSsl()) {
      options.setServer(request.serverAddress)
        .setMethod(request.method)
        .setSsl(request.ssl)
        .setHost(host)
        .setPort(port)
        .setURI(requestURI);
    } else {
      if (request.protocol != null && !request.protocol.equals("http") && !request.protocol.equals("https")) {
        // we have to create an abs url again to parse it in HttpClient
        try {
          URI uri = new URI(request.protocol, null, host, port, requestURI, null, null);
          options.setServer(request.serverAddress)
            .setMethod(request.method)
            .setAbsoluteURI(uri.toString());
        } catch (URISyntaxException ex) {
          fail(ex);
          return;
        }
      } else {
        options.setServer(request.serverAddress)
          .setMethod(request.method)
          .setHost(host)
          .setPort(port)
          .setURI(requestURI);
      }
    }
    redirects = 0;
    if (request.virtualHost != null) {
      if (options.getServer() == null) {
        options.setServer(SocketAddress.inetSocketAddress(options.getPort(), options.getHost()));
      }
      options.setHost(request.virtualHost);
    }
    createRequest(options);
  }

  private void handleCreateRequest() {
    if (request.headers != null) {
      MultiMap headers = requestOptions.getHeaders();
      if (headers == null) {
        headers = MultiMap.caseInsensitiveMultiMap();
        requestOptions.setHeaders(headers);
      }
      headers.addAll(request.headers);
    }
    if (contentType != null) {
      String prev = requestOptions.getHeaders().get(HttpHeaders.CONTENT_TYPE);
      if (prev == null) {
        requestOptions.addHeader(HttpHeaders.CONTENT_TYPE, contentType);
      } else {
        contentType = prev;
      }
    }
    requestOptions.setTimeout(this.request.timeout);
    requestPromise = Promise.promise();
    if (body != null || "application/json".equals(contentType)) {
      if (body instanceof MultiMap) {
        MultipartForm parts = MultipartForm.create();
        MultiMap attributes = (MultiMap) body;
        for (Map.Entry<String, String> attribute : attributes) {
          parts.attribute(attribute.getKey(), attribute.getValue());
        }
        body = parts;
      }
      if (body instanceof MultipartForm) {
        MultipartFormUpload multipartForm;
        try {
          boolean multipart = "multipart/form-data".equals(contentType);
          HttpPostRequestEncoder.EncoderMode encoderMode = this.request.multipartMixed ? HttpPostRequestEncoder.EncoderMode.RFC1738 : HttpPostRequestEncoder.EncoderMode.HTML5;
          multipartForm = new MultipartFormUpload(context,  (MultipartForm) this.body, multipart, encoderMode);
          this.body = multipartForm;
        } catch (Exception e) {
          fail(e);
          return;
        }
        for (String headerName : this.request.headers().names()) {
          requestOptions.putHeader(headerName, this.request.headers().get(headerName));
        }
        multipartForm.headers().forEach(header -> {
          requestOptions.putHeader(header.getKey(), header.getValue());
        });
      }
      if (body instanceof ReadStream<?>) {
        ReadStream<Buffer> stream = (ReadStream<Buffer>) body;
        Pipe<Buffer> pipe = stream.pipe(); // Shouldn't this be called in an earlier phase ?
        requestPromise.future().onComplete(ar -> {
          if (ar.succeeded()) {
            HttpClientRequest req = ar.result();
            if (this.request.headers == null || !this.request.headers.contains(HttpHeaders.CONTENT_LENGTH)) {
              req.setChunked(true);
            }
            pipe.endOnFailure(false);
            pipe.to(req, ar2 -> {
              clientRequest = null;
              if (ar2.failed()) {
                req.reset(0L, ar2.cause());
              }
            });
            if (body instanceof MultipartFormUpload) {
              ((MultipartFormUpload) body).run();
            }
          } else {
            // Test this
            clientRequest = null;
            pipe.close();
          }
        });
      } else {
        Buffer buffer;
        if (body instanceof Buffer) {
          buffer = (Buffer) body;
        } else if (body instanceof JsonObject) {
          buffer = Buffer.buffer(((JsonObject)body).encode());
        } else {
          buffer = Buffer.buffer(Json.encode(body));
        }
        requestPromise.future().onSuccess(request -> {
          clientRequest = null;
          request.putHeader(HttpHeaders.CONTENT_LENGTH, "" + buffer.length());
          request.end(buffer);
        });
      }
    } else {
      requestPromise.future().onSuccess(request -> {
        clientRequest = null;
        request.end();
      });
    }
    client.request(requestOptions)
      .onComplete(ar1 -> {
        if (ar1.succeeded()) {
          sendRequest(ar1.result());
        } else {
          fail(ar1.cause());
          requestPromise.fail(ar1.cause());
        }
      });
  }

  private void handleReceiveResponse() {
    HttpClientResponse resp = clientResponse;
    Context context = Vertx.currentContext();
    Promise<HttpResponse<T>> promise = Promise.promise();
    promise.future().onComplete(r -> {
      // We are running on a context (the HTTP client mandates it)
      context.runOnContext(v -> {
        if (r.succeeded()) {
          dispatchResponse(r.result());
        } else {
          fail(r.cause());
        }
      });
    });
    resp.exceptionHandler(err -> {
      if (!promise.future().isComplete()) {
        promise.fail(err);
      }
    });
    Pipe<Buffer> pipe = resp.pipe();
    request.codec.create(ar1 -> {
      if (ar1.succeeded()) {
        BodyStream<T> stream = ar1.result();
        pipe.to(stream, ar2 -> {
          if (ar2.succeeded()) {
            stream.result().onComplete(ar3 -> {
              if (ar3.succeeded()) {
                promise.complete(new HttpResponseImpl<>(
                  resp.version(),
                  resp.statusCode(),
                  resp.statusMessage(),
                  resp.headers(),
                  resp.trailers(),
                  resp.cookies(),
                  stream.result().result(),
                  redirectedLocations
                ));
              } else {
                promise.fail(ar3.cause());
              }
            });
          } else {
            promise.fail(ar2.cause());
          }
        });
      } else {
        pipe.close();
        fail(ar1.cause());
      }
    });
  }

  private void handleSendRequest() {
    clientRequest.response(ar -> {
      if (ar.succeeded()) {
        receiveResponse(ar.result().pause());
      } else {
        fail(ar.cause());
      }
    });
    requestPromise.complete(clientRequest);
  }

  public <T> T get(String key) {
    return attrs != null ? (T) attrs.get(key) : null;
  }

  public HttpContext<T> set(String key, Object value) {
    if (value == null) {
      if (attrs != null) {
        attrs.remove(key);
      }
    } else {
      if (attrs == null) {
        attrs = new HashMap<>();
      }
      attrs.put(key, value);
    }
    return this;
  }
}
