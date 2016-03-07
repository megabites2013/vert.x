/*
 * Copyright (c) 2011-2013 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.core.http.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.net.NetSocket;

import java.util.List;
import java.util.Objects;

import static io.vertx.core.http.HttpHeaders.*;

/**
 * This class is optimised for performance when used on the same event loop that is was passed to the handler with.
 * However it can be used safely from other threads.
 *
 * The internal state is protected using the synchronized keyword. If always used on the same event loop, then
 * we benefit from biased locking which makes the overhead of synchronized near zero.
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class HttpClientRequestImpl extends HttpClientRequestBase implements HttpClientRequest {

  private final String host;
  private final int port;
  private final boolean ssl;
  private final HttpClientImpl client;
  private final VertxInternal vertx;
  private final io.vertx.core.http.HttpMethod method;
  private final String uri;
  private Handler<HttpClientResponse> respHandler;
  private Handler<Void> endHandler;
  private boolean chunked;
  private String hostHeader;
  private Handler<Void> continueHandler;
  private volatile HttpClientStream conn;
  private Handler<Void> drainHandler;
  private Handler<HttpClientRequest> pushHandler;
  private Handler<HttpConnection> connectionHandler;
  private boolean headWritten;
  private boolean completed;
  private ByteBuf pendingChunks;
  private int pendingMaxSize = -1;
  private boolean connecting;
  private boolean writeHead;
  private long written;
  private CaseInsensitiveHeaders headers;

  HttpClientRequestImpl(HttpClientImpl client, io.vertx.core.http.HttpMethod method, String host, int port,
                        boolean ssl, String relativeURI, VertxInternal vertx) {
    super(client);
    this.host = host;
    this.port = port;
    this.client = client;
    this.chunked = false;
    this.method = method;
    this.vertx = vertx;
    this.uri = relativeURI;
    this.ssl = ssl;
  }

  @Override
  public  HttpClientRequest handler(Handler<HttpClientResponse> handler) {
    synchronized (getLock()) {
      if (handler != null) {
        checkComplete();
        respHandler = checkConnect(method, handler);
      } else {
        respHandler = null;
      }
      return this;
    }
  }

  @Override
  public HttpClientRequest pause() {
    return this;
  }

  @Override
  public HttpClientRequest resume() {
    return this;
  }

  @Override
  public HttpClientRequest endHandler(Handler<Void> endHandler) {
    synchronized (getLock()) {
      if (endHandler != null) {
        checkComplete();
      }
      this.endHandler = endHandler;
      return this;
    }
  }

  @Override
  public HttpClientRequestImpl setChunked(boolean chunked) {
    synchronized (getLock()) {
      checkComplete();
      if (written > 0) {
        throw new IllegalStateException("Cannot set chunked after data has been written on request");
      }
      // HTTP 1.0 does not support chunking so we ignore this if HTTP 1.0
      if (client.getOptions().getProtocolVersion() != io.vertx.core.http.HttpVersion.HTTP_1_0) {
        this.chunked = chunked;
      }
      return this;
    }
  }

  @Override
  public boolean isChunked() {
    synchronized (getLock()) {
      return chunked;
    }
  }

  @Override
  public HttpClientRequest setHost(String host) {
    synchronized (getLock()) {
      this.hostHeader = host;
      return this;
    }
  }

  @Override
  public String getHost() {
    synchronized (getLock()) {
      return hostHeader;
    }
  }

  @Override
  public io.vertx.core.http.HttpMethod method() {
    return method;
  }

  @Override
  public String uri() {
    return uri;
  }

  @Override
  public MultiMap headers() {
    synchronized (getLock()) {
      if (headers == null) {
        headers = new CaseInsensitiveHeaders();
      }
      return headers;
    }
  }

  @Override
  public HttpClientRequest putHeader(String name, String value) {
    synchronized (getLock()) {
      checkComplete();
      headers().set(name, value);
      return this;
    }
  }

  @Override
  public HttpClientRequest putHeader(String name, Iterable<String> values) {
    synchronized (getLock()) {
      checkComplete();
      headers().set(name, values);
      return this;
    }
  }

  @Override
  public HttpClientRequestImpl write(Buffer chunk) {
    synchronized (getLock()) {
      checkComplete();
      checkResponseHandler();
      ByteBuf buf = chunk.getByteBuf();
      write(buf, false);
      return this;
    }
  }

  @Override
  public HttpClientRequestImpl write(String chunk) {
    synchronized (getLock()) {
      checkComplete();
      checkResponseHandler();
      return write(Buffer.buffer(chunk));
    }
  }

  @Override
  public HttpClientRequestImpl write(String chunk, String enc) {
    synchronized (getLock()) {
      Objects.requireNonNull(enc, "no null encoding accepted");
      checkComplete();
      checkResponseHandler();
      return write(Buffer.buffer(chunk, enc));
    }
  }

  @Override
  public HttpClientRequest setWriteQueueMaxSize(int maxSize) {
    synchronized (getLock()) {
      checkComplete();
      if (conn != null) {
        conn.doSetWriteQueueMaxSize(maxSize);
      } else {
        pendingMaxSize = maxSize;
      }
      return this;
    }
  }

  @Override
  public boolean writeQueueFull() {
    synchronized (getLock()) {
      checkComplete();
      return conn != null && conn.isNotWritable();
    }
  }

  @Override
  public HttpClientRequest drainHandler(Handler<Void> handler) {
    synchronized (getLock()) {
      checkComplete();
      this.drainHandler = handler;
      if (conn != null) {
        conn.getContext().runOnContext(v -> conn.handleInterestedOpsChanged());
      }
      return this;
    }
  }

  @Override
  public HttpClientRequest continueHandler(Handler<Void> handler) {
    synchronized (getLock()) {
      checkComplete();
      this.continueHandler = handler;
      return this;
    }
  }

  @Override
  public HttpClientRequestImpl sendHead() {
    synchronized (getLock()) {
      checkComplete();
      checkResponseHandler();
      if (conn != null) {
        if (!headWritten) {
          writeHead();
        }
      } else {
        connect();
        writeHead = true;
      }
      return this;
    }
  }

  @Override
  public void end(String chunk) {
    synchronized (getLock()) {
      end(Buffer.buffer(chunk));
    }
  }

  @Override
  public void end(String chunk, String enc) {
    synchronized (getLock()) {
      Objects.requireNonNull(enc, "no null encoding accepted");
      end(Buffer.buffer(chunk, enc));
    }
  }

  @Override
  public void end(Buffer chunk) {
    synchronized (getLock()) {
      checkComplete();
      checkResponseHandler();
      if (!chunked && !contentLengthSet()) {
        headers().set(CONTENT_LENGTH, String.valueOf(chunk.length()));
      }
      write(chunk.getByteBuf(), true);
    }
  }

  @Override
  public void end() {
    synchronized (getLock()) {
      checkComplete();
      checkResponseHandler();
      write(Unpooled.EMPTY_BUFFER, true);
    }
  }

  @Override
  public HttpClientRequest putHeader(CharSequence name, CharSequence value) {
    synchronized (getLock()) {
      checkComplete();
      headers().set(name, value);
      return this;
    }
  }

  @Override
  public HttpClientRequest putHeader(CharSequence name, Iterable<CharSequence> values) {
    synchronized (getLock()) {
      checkComplete();
      headers().set(name, values);
      return this;
    }
  }

  @Override
  public HttpClientRequest pushPromiseHandler(Handler<HttpClientRequest> handler) {
    synchronized (getLock()) {
      pushHandler = handler;
    }
    return this;
  }

  @Override
  public void reset(long code) {
    synchronized (getLock()) {
      if (conn == null) {
        throw new IllegalStateException("Cannot reset the request that is not yet connected");
      }
      conn.reset(code);
    }
  }

  @Override
  public HttpConnection connection() {
    synchronized (getLock()) {
      if (conn == null) {
        throw new IllegalStateException("Not yet connected");
      }
      return (HttpConnection) conn.connection();
    }
  }

  @Override
  public HttpClientRequest connectionHandler(@Nullable Handler<HttpConnection> handler) {
    synchronized (getLock()) {
      connectionHandler = handler;
      return this;
    }
  }

  void handleDrained() {
    synchronized (getLock()) {
      if (!completed && drainHandler != null) {
        try {
          drainHandler.handle(null);
        } catch (Throwable t) {
          handleException(t);
        }
      }
    }
  }

  protected void doHandleResponse(HttpClientResponseImpl resp) {
    if (resp.statusCode() == 100) {
      if (continueHandler != null) {
        continueHandler.handle(null);
      }
    } else {
      if (respHandler != null) {
        respHandler.handle(resp);
      }
      if (endHandler != null) {
        endHandler.handle(null);
      }
    }
  }

  io.vertx.core.http.HttpMethod getMethod() {
    return method;
  }

  // After connecting we should synchronize on the client connection instance to prevent deadlock conditions
  // but there is a catch - the client connection is null before connecting so we synchronized on this before that
  // point
  protected Object getLock() {
    // We do the initial check outside the synchronized block to prevent the hit of synchronized once the conn has
    // been set
    if (conn != null) {
      return conn;
    } else {
      synchronized (this) {
        if (conn != null) {
          return conn;
        } else {
          return this;
        }
      }
    }
  }

  private Handler<HttpClientResponse> checkConnect(io.vertx.core.http.HttpMethod method, Handler<HttpClientResponse> handler) {
    if (method == io.vertx.core.http.HttpMethod.CONNECT) {
      // special handling for CONNECT
      handler = connectHandler(handler);
    }
    return handler;
  }

  private Handler<HttpClientResponse> connectHandler(Handler<HttpClientResponse> responseHandler) {
    Objects.requireNonNull(responseHandler, "no null responseHandler accepted");
    return resp -> {
      HttpClientResponse response;
      if (resp.statusCode() == 200) {
        // connect successful force the modification of the ChannelPipeline
        // beside this also pause the socket for now so the user has a chance to register its dataHandler
        // after received the NetSocket
        NetSocket socket = resp.netSocket();
        socket.pause();

        response = new HttpClientResponse() {
          private boolean resumed;

          @Override
          public int statusCode() {
            return resp.statusCode();
          }

          @Override
          public String statusMessage() {
            return resp.statusMessage();
          }

          @Override
          public MultiMap headers() {
            return resp.headers();
          }

          @Override
          public String getHeader(String headerName) {
            return resp.getHeader(headerName);
          }

          @Override
          public String getHeader(CharSequence headerName) {
            return resp.getHeader(headerName);
          }

          @Override
          public String getTrailer(String trailerName) {
            return resp.getTrailer(trailerName);
          }

          @Override
          public MultiMap trailers() {
            return resp.trailers();
          }

          @Override
          public List<String> cookies() {
            return resp.cookies();
          }

          @Override
          public HttpVersion version() {
            return resp.version();
          }

          @Override
          public HttpClientResponse bodyHandler(Handler<Buffer> bodyHandler) {
            resp.bodyHandler(bodyHandler);
            return this;
          }

          @Override
          public synchronized NetSocket netSocket() {
            if (!resumed) {
              resumed = true;
              vertx.getContext().runOnContext((v) -> socket.resume()); // resume the socket now as the user had the chance to register a dataHandler
            }
            return socket;
          }

          @Override
          public HttpClientResponse endHandler(Handler<Void> endHandler) {
            resp.endHandler(endHandler);
            return this;
          }

          @Override
          public HttpClientResponse handler(Handler<Buffer> handler) {
            resp.handler(handler);
            return this;
          }

          @Override
          public HttpClientResponse pause() {
            resp.pause();
            return this;
          }

          @Override
          public HttpClientResponse resume() {
            resp.resume();
            return this;
          }

          @Override
          public HttpClientResponse exceptionHandler(Handler<Throwable> handler) {
            resp.exceptionHandler(handler);
            return this;
          }
        };
      } else {
        response = resp;
      }
      responseHandler.handle(response);
    };
  }

  private synchronized void connect() {
    if (!connecting) {

      Waiter waiter = new Waiter(this, vertx.getContext()) {

        @Override
        void handleFailure(Throwable failure) {
          handleException(failure);
        }

        @Override
        void handleSuccess(HttpClientConnection conn, boolean connected) {
          synchronized (HttpClientRequestImpl.this) {
            if (connected && connectionHandler != null && conn instanceof HttpConnection) {
              connectionHandler.handle((HttpConnection) conn);
            }
            connected(conn);
          }
        }

        @Override
        boolean isCancelled() {
          // No need to synchronize as the thread is the same that set exceptionOccurred to true
          // exceptionOccurred=true getting the connection => it's a TimeoutException
          return exceptionOccurred;
        }
      };

      // We defer actual connection until the first part of body is written or end is called
      // This gives the user an opportunity to set an exception handler before connecting so
      // they can capture any exceptions on connection
      client.getConnection(port, host, waiter);
      connecting = true;
    }
  }

  private void connected(HttpClientConnection s) {

    conn = s.beginRequest(this);

    if (this.conn instanceof ClientConnection) {
      ClientConnection http1Conn = (ClientConnection) this.conn;
      this.metric = client.httpClientMetrics().requestBegin(http1Conn.metric(), http1Conn.localAddress(), http1Conn.remoteAddress(), this);
    }

    // If anything was written or the request ended before we got the connection, then
    // we need to write it now

    if (pendingMaxSize != -1) {
      this.conn.doSetWriteQueueMaxSize(pendingMaxSize);
    }

    if (pendingChunks != null) {
      ByteBuf pending = pendingChunks;
      pendingChunks = null;

      if (completed) {
        // we also need to write the head so optimize this and write all out in once
        writeHeadWithContent(pending, true);

        s.reportBytesWritten(written);

        if (respHandler != null) {
          this.conn.endRequest();
        }
      } else {
        writeHeadWithContent(pending, false);
      }
    } else {
      if (completed) {
        // we also need to write the head so optimize this and write all out in once
        writeHeadWithContent(Unpooled.EMPTY_BUFFER, true);

        s.reportBytesWritten(written);

        if (respHandler != null) {
          this.conn.endRequest();
        }
      } else {
        if (writeHead) {
          writeHead();
        }
      }
    }
  }

  private boolean contentLengthSet() {
    return headers != null && headers().contains(CONTENT_LENGTH);
  }

  private String hostHeader() {
    if (hostHeader != null) {
      return hostHeader;
    }
    if ((port == 80 && !ssl) || (port == 443 && ssl)) {
      return host;
    } else {
      return host + ':' + port;
    }
  }

  private void writeHead() {
    conn.writeHead(method, uri, headers, hostHeader(), chunked);
    headWritten = true;
  }

  private void writeHeadWithContent(ByteBuf buf, boolean end) {
    conn.writeHeadWithContent(method, uri, headers, hostHeader(), chunked, buf, end);
    headWritten = true;
  }

  private void write(ByteBuf buff, boolean end) {
    int readableBytes = buff.readableBytes();
    if (readableBytes == 0 && !end) {
      // nothing to write to the connection just return
      return;
    }

    if (end) {
      completed = true;
    }
    if (!end && !chunked && !contentLengthSet()) {
      throw new IllegalStateException("You must set the Content-Length header to be the total size of the message "
              + "body BEFORE sending any data if you are not using HTTP chunked encoding.");
    }

    written += buff.readableBytes();
    if (conn == null) {
      if (pendingChunks == null) {
        pendingChunks = buff;
      } else {
        CompositeByteBuf pending;
        if (pendingChunks instanceof CompositeByteBuf) {
          pending = (CompositeByteBuf) pendingChunks;
        } else {
          pending = Unpooled.compositeBuffer();
          pending.addComponent(pendingChunks).writerIndex(pendingChunks.writerIndex());
          pendingChunks = pending;
        }
        pending.addComponent(buff).writerIndex(pending.writerIndex() + buff.writerIndex());
      }
      connect();
    } else {
      if (!headWritten) {
        writeHeadWithContent(buff, end);
      } else {
        conn.writeBuffer(buff, end);
      }
      if (end) {
        conn.connection().reportBytesWritten(written);

        if (respHandler != null) {
          conn.endRequest();
        }
      }
    }
  }

  protected void checkComplete() {
    if (completed) {
      throw new IllegalStateException("Request already complete");
    }
  }

  private void checkResponseHandler() {
    if (respHandler == null) {
      throw new IllegalStateException("You must set an handler for the HttpClientResponse before connecting");
    }
  }

  void handlePush(HttpClientRequest pushedRequest) {
    synchronized (getLock()) {
      if (pushHandler != null) {
        pushHandler.handle(pushedRequest);
      }
    }
  }
}
