/*
 * Jooby https://jooby.io
 * Apache License Version 2.0 https://jooby.io/LICENSE.txt
 * Copyright 2014 Edgar Espina
 */
package io.jooby.netty;

import static io.jooby.ServerOptions._4KB;
import static io.jooby.ServerOptions._8KB;
import static java.util.concurrent.Executors.newFixedThreadPool;

import java.net.BindException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

import javax.net.ssl.SSLContext;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jooby.Jooby;
import io.jooby.Server;
import io.jooby.ServerOptions;
import io.jooby.SneakyThrows;
import io.jooby.SslOptions;
import io.jooby.internal.netty.*;
import io.jooby.netty.buffer.NettyDataBufferFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.DiskAttribute;
import io.netty.handler.codec.http.multipart.DiskFileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.IdentityCipherSuiteFilter;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * Web server implementation using <a href="https://netty.io/">Netty</a>.
 *
 * @author edgar
 * @since 2.0.0
 */
public class NettyServer extends Server.Base {
  private static final int _50 = 50;

  private static final int _100 = 100;

  private List<Jooby> applications = new ArrayList<>();
  private EventLoopGroup acceptorloop;
  private EventLoopGroup eventloop;
  private EventLoopGroup dateLoop;
  private ExecutorService worker;

  private NettyDataBufferFactory bufferFactory;

  private ServerOptions options = new ServerOptions().setServer("netty");

  /**
   * Creates a server.
   *
   * @param bufferFactory Byte buffer allocator.
   * @param worker Thread-pool to use.
   */
  public NettyServer(
      @NonNull NettyDataBufferFactory bufferFactory, @NonNull ExecutorService worker) {
    this.worker = worker;
    this.bufferFactory = bufferFactory;
  }

  /**
   * Creates a server.
   *
   * @param worker Thread-pool to use.
   */
  public NettyServer(@NonNull ExecutorService worker) {
    this.worker = worker;
  }

  /**
   * Creates a server.
   *
   * @param bufferFactory Byte buffer allocator.
   */
  public NettyServer(@NonNull NettyDataBufferFactory bufferFactory) {
    this.bufferFactory = bufferFactory;
  }

  /** Creates a server. */
  public NettyServer() {}

  @Override
  public @NonNull NettyServer setOptions(@NonNull ServerOptions options) {
    this.options = options;
    return this;
  }

  @NonNull @Override
  public ServerOptions getOptions() {
    return options;
  }

  @NonNull @Override
  public String getName() {
    return "netty";
  }

  @NonNull @Override
  public Server start(@NonNull Jooby application) {
    try {
      /* Worker: Application blocking code */
      if (worker == null) {
        worker = newFixedThreadPool(options.getWorkerThreads(), new DefaultThreadFactory("worker"));
      }
      if (bufferFactory == null) {
        bufferFactory = new NettyDataBufferFactory(ByteBufAllocator.DEFAULT);
      }
      // Make sure context use same buffer factory
      application.setBufferFactory(bufferFactory);
      applications.add(application);

      addShutdownHook();

      fireStart(applications, worker);

      /* Disk attributes: */
      String tmpdir = applications.get(0).getTmpdir().toString();
      DiskFileUpload.baseDirectory = tmpdir;
      DiskAttribute.baseDirectory = tmpdir;

      var transport = NettyTransport.transport(application.getClassLoader());

      /* Acceptor event-loop */
      this.acceptorloop = transport.createEventLoop(1, "acceptor", _50);

      /* Event loop: processing connections, parsing messages and doing engine's internal work */
      this.eventloop = transport.createEventLoop(options.getIoThreads(), "eventloop", _100);
      this.dateLoop = transport.createEventLoop(1, "date-service", _100);
      var dateService = new NettyDateService(dateLoop);

      /* File data factory: */
      var factory = new DefaultHttpDataFactory(options.getBufferSize());
      var allocator = bufferFactory.getByteBufAllocator();
      var http2 = options.isHttp2() == Boolean.TRUE;
      /* Bootstrap: */
      if (!options.isHttpsOnly()) {
        var http =
            newBootstrap(allocator, transport, newPipeline(null, factory, dateService, http2));
        http.bind(options.getHost(), options.getPort()).get();
      }

      if (options.isSSLEnabled()) {
        var javaSslContext = options.getSSLContext(application.getEnvironment().getClassLoader());

        var sslOptions = options.getSsl();
        var protocol = sslOptions.getProtocol().toArray(String[]::new);

        var clientAuth = sslOptions.getClientAuth();
        var sslContext = wrap(javaSslContext, toClientAuth(clientAuth), protocol, http2);
        var https =
            newBootstrap(
                allocator, transport, newPipeline(sslContext, factory, dateService, http2));
        https.bind(options.getHost(), options.getSecurePort()).get();
      } else if (options.isHttpsOnly()) {
        throw new IllegalArgumentException(
            "Server configured for httpsOnly, but ssl options not set");
      }

      fireReady(applications);
    } catch (InterruptedException x) {
      throw SneakyThrows.propagate(x);
    } catch (ExecutionException x) {
      var cause = x.getCause();
      if (Server.isAddressInUse(cause)) {
        cause = new BindException("Address already in use: " + options.getPort());
      }
      throw SneakyThrows.propagate(cause);
    }
    return this;
  }

  private ServerBootstrap newBootstrap(
      ByteBufAllocator allocator, NettyTransport transport, NettyPipeline factory) {
    return transport
        .configure(acceptorloop, eventloop)
        .childHandler(factory)
        .childOption(ChannelOption.ALLOCATOR, allocator)
        .childOption(ChannelOption.SO_REUSEADDR, true)
        .childOption(ChannelOption.TCP_NODELAY, true);
  }

  private ClientAuth toClientAuth(SslOptions.ClientAuth clientAuth) {
    return switch (clientAuth) {
      case REQUIRED -> ClientAuth.REQUIRE;
      case REQUESTED -> ClientAuth.OPTIONAL;
      default -> ClientAuth.NONE;
    };
  }

  private NettyPipeline newPipeline(
      SslContext sslContext, HttpDataFactory factory, NettyDateService dateService, boolean http2) {
    var router = applications.get(0);
    var bufferSize = options.getBufferSize();
    var headersFactory = HeadersMultiMap.httpHeadersFactory();
    var decoderConfig =
        new HttpDecoderConfig()
            .setMaxInitialLineLength(_4KB)
            .setMaxHeaderSize(_8KB)
            .setMaxChunkSize(bufferSize)
            .setHeadersFactory(headersFactory)
            .setTrailersFactory(headersFactory);
    return new NettyPipeline(
        sslContext,
        dateService,
        factory,
        decoderConfig,
        router,
        options.getMaxRequestSize(),
        bufferSize,
        options.getDefaultHeaders(),
        http2,
        options.isExpectContinue() == Boolean.TRUE,
        options.getCompressionLevel());
  }

  @NonNull @Override
  public synchronized Server stop() {
    fireStop(applications);
    applications.clear();
    // only for jooby build where close events may take longer.
    NettyWebSocket.all.clear();

    shutdown(acceptorloop);
    shutdown(eventloop);
    shutdown(dateLoop);
    if (worker != null) {
      worker.shutdown();
      worker = null;
    }
    return this;
  }

  private void shutdown(EventLoopGroup eventLoopGroup) {
    if (eventLoopGroup != null) {
      eventLoopGroup.shutdownGracefully();
    }
  }

  private SslContext wrap(
      SSLContext sslContext, ClientAuth clientAuth, String[] protocol, boolean http2) {
    ApplicationProtocolConfig protocolConfig;
    if (http2) {
      protocolConfig =
          new ApplicationProtocolConfig(
              ApplicationProtocolConfig.Protocol.ALPN,
              ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
              ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
              Arrays.asList(ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1));
    } else {
      protocolConfig = ApplicationProtocolConfig.DISABLED;
    }
    return new JdkSslContext(
        sslContext,
        false,
        null,
        IdentityCipherSuiteFilter.INSTANCE,
        protocolConfig,
        clientAuth,
        protocol,
        false);
  }
}
