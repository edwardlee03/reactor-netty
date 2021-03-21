/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.quic;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicChannelBootstrap;
import io.netty.util.AttributeKey;
import io.netty.util.NetUtil;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Operators;
import reactor.netty.ChannelBindException;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.transport.AddressUtils;
import reactor.netty.transport.TransportConnector;
import reactor.util.context.Context;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static reactor.netty.ConnectionObserver.State.CONFIGURED;

/**
 * Provides the actual {@link QuicClient} instance.
 *
 * @author Violeta Georgieva
 */
final class QuicClientConnect extends QuicClient {

	static final QuicClientConnect INSTANCE = new QuicClientConnect();

	final QuicClientConfig config;

	QuicClientConnect() {
		this.config = new QuicClientConfig(
				Collections.emptyMap(),
				Collections.singletonMap(ChannelOption.AUTO_READ, false),
				() -> new InetSocketAddress(NetUtil.LOCALHOST, 0),
				() -> new InetSocketAddress(NetUtil.LOCALHOST, DEFAULT_PORT));
	}

	QuicClientConnect(QuicClientConfig config) {
		this.config = config;
	}

	@Override
	public QuicClientConfig configuration() {
		return config;
	}

	@Override
	public Mono<? extends QuicConnection> connect() {
		QuicClientConfig config = configuration();
		Objects.requireNonNull(config.bindAddress(), "bindAddress");
		Objects.requireNonNull(config.remoteAddress(), "remoteAddress");

		Mono<? extends QuicConnection> mono = Mono.create(sink -> {
			SocketAddress local = Objects.requireNonNull(config.bindAddress().get(), "Bind Address supplier returned null");
			if (local instanceof InetSocketAddress) {
				InetSocketAddress localInet = (InetSocketAddress) local;

				if (localInet.isUnresolved()) {
					local = AddressUtils.createResolved(localInet.getHostName(), localInet.getPort());
				}
			}

			DisposableConnect disposableConnect = new DisposableConnect(config, sink);
			TransportConnector.bind(config, config.parentChannelInitializer(), local, false)
			                  .subscribe(disposableConnect);
		});

		if (config.doOnConnect != null) {
			mono = mono.doOnSubscribe(s -> config.doOnConnect.accept(config));
		}

		return mono;
	}

	@Override
	protected QuicClient duplicate() {
		return new QuicClientConnect(new QuicClientConfig(config));
	}

	/**
	 * The default port for reactor-netty clients. Defaults to 12012 but can be tuned via
	 * the {@code PORT} <b>environment variable</b>.
	 */
	static final int DEFAULT_PORT = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 12012;

	static final class DisposableConnect implements CoreSubscriber<Channel>, Disposable {

		final Map<AttributeKey<?>, ?>           attributes;
		final Supplier<? extends SocketAddress> bindAddress;
		final Map<ChannelOption<?>, ?>          options;
		final ChannelInitializer<Channel>       quicChannelInitializer;
		final Supplier<? extends SocketAddress> remoteAddress;
		final MonoSink<QuicConnection>          sink;
		final Map<AttributeKey<?>, ?>           streamAttrs;
		final Map<ChannelOption<?>, ?>          streamOptions;

		Subscription subscription;

		DisposableConnect(QuicClientConfig config, MonoSink<QuicConnection> sink) {
			this.attributes = config.attributes();
			this.bindAddress = config.bindAddress();
			this.options = config.options();
			ConnectionObserver observer = new QuicChannelObserver(
					config.defaultConnectionObserver().then(config.connectionObserver()),
					sink);
			this.quicChannelInitializer = config.channelInitializer(observer, null, false);
			this.remoteAddress = config.remoteAddress;
			this.sink = sink;
			this.streamAttrs = config.streamAttrs;
			this.streamOptions = config.streamOptions;
		}

		@Override
		public Context currentContext() {
			return sink.currentContext();
		}

		@Override
		public void dispose() {
			subscription.cancel();
		}

		@Override
		public void onComplete() {
		}

		@Override
		public void onError(Throwable t) {
			if (bindAddress != null && (t instanceof BindException ||
					// With epoll/kqueue transport it is
					// io.netty.channel.unix.Errors$NativeIoException: bind(..) failed: Address already in use
					(t instanceof IOException && t.getMessage() != null &&
							t.getMessage().contains("Address already in use")))) {
				sink.error(ChannelBindException.fail(bindAddress.get(), null));
			}
			else {
				sink.error(t);
			}
		}

		@Override
		public void onNext(Channel channel) {
			if (log.isDebugEnabled()) {
				log.debug("Bound new channel");
			}

			SocketAddress remote = null;
			if (remoteAddress != null) {
				remote = Objects.requireNonNull(remoteAddress.get(), "Remote Address supplier returned null");
			}

			QuicChannelBootstrap bootstrap =
					QuicChannel.newBootstrap(channel)
					           .remoteAddress(remote)
					           .handler(quicChannelInitializer)
					           .streamHandler(new ChannelInitializer<Channel>() {

								@Override
								protected void initChannel(Channel ch) {
									// TODO the server opened a stream
								}
							});

			attributes(bootstrap, attributes);
			channelOptions(bootstrap, options);
			streamAttributes(bootstrap, streamAttrs);
			streamChannelOptions(bootstrap, streamOptions);

			//TODO do we need to attach a listener, we've already configured QuicChannelObserver
			bootstrap.connect();
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(subscription, s)) {
				this.subscription = s;
				sink.onCancel(this);
				s.request(Long.MAX_VALUE);
			}
		}

		@SuppressWarnings("unchecked")
		static void attributes(QuicChannelBootstrap bootstrap, Map<AttributeKey<?>, ?> attrs) {
			for (Map.Entry<AttributeKey<?>, ?> e : attrs.entrySet()) {
				bootstrap.attr((AttributeKey<Object>) e.getKey(), e.getValue());
			}
		}

		@SuppressWarnings("unchecked")
		static void channelOptions(QuicChannelBootstrap bootstrap, Map<ChannelOption<?>, ?> options) {
			for (Map.Entry<ChannelOption<?>, ?> e : options.entrySet()) {
				bootstrap.option((ChannelOption<Object>) e.getKey(), e.getValue());
			}
		}

		@SuppressWarnings("unchecked")
		static void streamAttributes(QuicChannelBootstrap bootstrap, Map<AttributeKey<?>, ?> attrs) {
			for (Map.Entry<AttributeKey<?>, ?> e : attrs.entrySet()) {
				bootstrap.streamAttr((AttributeKey<Object>) e.getKey(), e.getValue());
			}
		}

		@SuppressWarnings("unchecked")
		static void streamChannelOptions(QuicChannelBootstrap bootstrap, Map<ChannelOption<?>, ?> options) {
			for (Map.Entry<ChannelOption<?>, ?> e : options.entrySet()) {
				bootstrap.streamOption((ChannelOption<Object>) e.getKey(), e.getValue());
			}
		}
	}

	static final class QuicChannelObserver implements ConnectionObserver {

		final ConnectionObserver       childObs;
		final MonoSink<QuicConnection> sink;

		QuicChannelObserver(ConnectionObserver childObs, MonoSink<QuicConnection> sink) {
			this.childObs = childObs;
			this.sink = sink;
		}

		@Override
		public void onUncaughtException(Connection connection, Throwable error) {
			sink.error(error);
			childObs.onUncaughtException(connection, error);
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if (newState == CONFIGURED) {
				sink.success((QuicConnection) Connection.from(connection.channel()));
			}

			childObs.onStateChange(connection, newState);
		}
	}
}