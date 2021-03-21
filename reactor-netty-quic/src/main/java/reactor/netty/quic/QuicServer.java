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

import io.netty.incubator.codec.quic.QuicConnectionIdGenerator;
import io.netty.incubator.codec.quic.QuicTokenHandler;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.transport.AddressUtils;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;

import static reactor.netty.ConnectionObserver.State.CONFIGURED;

/**
 * A QuicServer allows to build in a safe immutable way a QUIC server that is materialized
 * and bound when {@link #bind()} is ultimately called.
 * <p>
 * <p> Example:
 * <pre>
 * {@code
 *     QuicServer.create()
 *               .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
 *               .port(7777)
 *               .wiretap(true)
 *               .secure(serverCtx)
 *               .idleTimeout(Duration.ofSeconds(5))
 *               .initialSettings(spec ->
 *                   spec.maxData(10000000)
 *                       .maxStreamDataBidirectionalLocal(1000000)
 *                       .maxStreamDataBidirectionalRemote(1000000)
 *                       .maxStreamsBidirectional(100)
 *                       .maxStreamsUnidirectional(100))
 *               .bindNow();
 * }
 * </pre>
 *
 * @author Violeta Georgieva
 */
public abstract class QuicServer extends QuicTransport<QuicServer, QuicServerConfig> {

	/**
	 * Prepare a {@link QuicServer}
	 *
	 * @return a {@link QuicServer}
	 */
	public static QuicServer create() {
		return QuicServerBind.INSTANCE;
	}

	/**
	 * Binds the {@link QuicServer} and returns a {@link Mono} of {@link Connection}. If
	 * {@link Mono} is cancelled, the underlying binding will be aborted. Once the {@link
	 * Connection} has been emitted and is not necessary anymore, disposing the main server
	 * loop must be done by the user via {@link Connection#dispose()}.
	 *
	 * @return a {@link Mono} of {@link Connection}
	 */
	public abstract Mono<? extends Connection> bind();

	/**
	 * Starts the server in a blocking fashion, and waits for it to finish initializing
	 * or the startup timeout expires (the startup timeout is {@code 45} seconds). The
	 * returned {@link Connection} offers simple server API, including to {@link
	 * Connection#disposeNow()} shut it down in a blocking fashion.
	 *
	 * @return a {@link Connection}
	 */
	public final Connection bindNow() {
		return bindNow(Duration.ofSeconds(45));
	}

	/**
	 * Start the server in a blocking fashion, and wait for it to finish initializing
	 * or the provided startup timeout expires. The returned {@link Connection}
	 * offers simple server API, including to {@link Connection#disposeNow()}
	 * shut it down in a blocking fashion.
	 *
	 * @param timeout max startup timeout (resolution: ns)
	 * @return a {@link Connection}
	 */
	public final Connection bindNow(Duration timeout) {
		Objects.requireNonNull(timeout, "timeout");
		try {
			return Objects.requireNonNull(bind().block(timeout), "aborted");
		}
		catch (IllegalStateException e) {
			if (e.getMessage().contains("blocking read")) {
				throw new IllegalStateException("QuicServer couldn't be started within " + timeout.toMillis() + "ms");
			}
			throw e;
		}
	}

	/**
	 * Set the {@link QuicConnectionIdGenerator} to use.
	 * Default to {@link QuicConnectionIdGenerator#randomGenerator()}.
	 *
	 * @param connectionIdAddressGenerator  the {@link QuicConnectionIdGenerator} to use.
	 * @return a new {@link QuicServer} reference
	 */
	public final QuicServer connectionIdAddressGenerator(QuicConnectionIdGenerator connectionIdAddressGenerator) {
		Objects.requireNonNull(connectionIdAddressGenerator, "connectionIdAddressGenerator");
		QuicServer dup = duplicate();
		dup.configuration().connectionIdAddressGenerator = connectionIdAddressGenerator;
		return dup;
	}

	/**
	 * Attach an IO handler to react on incoming stream
	 *
	 * @param streamHandler an IO handler that can dispose underlying connection when {@link
	 * Publisher} terminates.
	 *
	 * @return a new {@link QuicServer}
	 */
	public final QuicServer handleStream(
			BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> streamHandler) {
		Objects.requireNonNull(streamHandler, "streamHandler");
		return streamObserve(new QuicStreamChannelObserver(streamHandler));
	}

	/**
	 * The host to which this server should bind.
	 *
	 * @param host the host to bind to.
	 * @return a new {@link QuicServer} reference
	 */
	public final QuicServer host(String host) {
		return bindAddress(() -> AddressUtils.updateHost(configuration().bindAddress(), host));
	}

	/**
	 * The port to which this server should bind.
	 *
	 * @param port The port to bind to.
	 * @return a new {@link QuicServer} reference
	 */
	public final QuicServer port(int port) {
		return bindAddress(() -> AddressUtils.updatePort(configuration().bindAddress(), port));
	}

	/**
	 * Configure the {@link QuicTokenHandler} that is used to generate and validate tokens.
	 *
	 * @param tokenHandler the {@link QuicTokenHandler} to use
	 * @return a {@link QuicServer}
	 */
	public final QuicServer tokenHandler(QuicTokenHandler tokenHandler) {
		Objects.requireNonNull(tokenHandler, "tokenHandler");
		QuicServer dup = duplicate();
		dup.configuration().tokenHandler = tokenHandler;
		return dup;
	}

	static final Logger log = Loggers.getLogger(QuicServer.class);

	static final class QuicStreamChannelObserver implements ConnectionObserver {

		final BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> streamHandler;

		QuicStreamChannelObserver(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> streamHandler) {
			this.streamHandler = streamHandler;
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void onStateChange(Connection connection, State newState) {
			if (newState == CONFIGURED) {
				try {
					if (log.isDebugEnabled()) {
						log.debug("Handler is being applied: {}", streamHandler);
					}

					QuicStreamOperations ops = (QuicStreamOperations) connection;
					Mono.fromDirect(streamHandler.apply(ops, ops))
					    .subscribe(ops.disposeSubscriber());
				}
				catch (Throwable t) {
					log.error("", t);

					//"FutureReturnValueIgnored" this is deliberate
					connection.channel()
					          .close();
				}
			}
		}
	}
}