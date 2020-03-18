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
package reactor.netty.resources;

import io.micrometer.core.instrument.Gauge;
import reactor.pool.InstrumentedPool;

import static reactor.netty.Metrics.ACTIVE_CONNECTIONS;
import static reactor.netty.Metrics.CONNECTION_PROVIDER_PREFIX;
import static reactor.netty.Metrics.ID;
import static reactor.netty.Metrics.IDLE_CONNECTIONS;
import static reactor.netty.Metrics.PENDING_CONNECTIONS;
import static reactor.netty.Metrics.NAME;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.TOTAL_CONNECTIONS;

/**
 * @author Violeta Georgieva
 * @since 0.9
 */
final class PooledConnectionProviderMetrics {

	static void registerMetrics(String poolName, String id, String remoteAddress,
			InstrumentedPool.PoolMetrics metrics) {
		// This is for backwards compatibility and will be removed in the next versions
		String[] tags = new String[] {ID, id, REMOTE_ADDRESS, remoteAddress};
		registerMetricsInternal(CONNECTION_PROVIDER_PREFIX + "." + poolName, metrics, tags);

		tags = new String[] {ID, id, REMOTE_ADDRESS, remoteAddress, NAME, poolName};
		registerMetricsInternal(CONNECTION_PROVIDER_PREFIX, metrics, tags);
	}

	private static void registerMetricsInternal(String name, InstrumentedPool.PoolMetrics metrics, String... tags) {
		Gauge.builder(name + TOTAL_CONNECTIONS, metrics, InstrumentedPool.PoolMetrics::allocatedSize)
		     .description("The number of all connections, active or idle.")
		     .tags(tags)
		     .register(REGISTRY);

		Gauge.builder(name + ACTIVE_CONNECTIONS, metrics, InstrumentedPool.PoolMetrics::acquiredSize)
		     .description("The number of the connections that have been successfully acquired and are in active use")
		     .tags(tags)
		     .register(REGISTRY);

		Gauge.builder(name + IDLE_CONNECTIONS, metrics, InstrumentedPool.PoolMetrics::idleSize)
		     .description("The number of the idle connections")
		     .tags(tags)
		     .register(REGISTRY);

		Gauge.builder(name + PENDING_CONNECTIONS, metrics, InstrumentedPool.PoolMetrics::pendingAcquireSize)
		     .description("The number of the request, that are pending acquire a connection")
		     .tags(tags)
		     .register(REGISTRY);
	}
}