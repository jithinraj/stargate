/*
 * Copyright The Stargate Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.stargate.cql.impl;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import org.apache.cassandra.stargate.metrics.ClientMetrics;
import org.apache.cassandra.stargate.transport.internal.Server;
import org.apache.cassandra.stargate.transport.internal.TransportDescriptor;
import org.apache.cassandra.config.Config;
import org.apache.cassandra.utils.NativeLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.stargate.db.Persistence;

public class CqlImpl {
    private static final Logger logger = LoggerFactory.getLogger(CqlImpl.class);

    private Collection<Server> servers = Collections.emptyList();
    final private EventLoopGroup workerGroup;

    public CqlImpl(Config config) {
        TransportDescriptor.daemonInitialization(config);

        if (useEpoll())
        {
            workerGroup = new EpollEventLoopGroup();
            logger.info("Netty using native Epoll event loop");
        }
        else
        {
            workerGroup = new NioEventLoopGroup();
            logger.info("Netty using Java NIO event loop");
        }

    }

    public void start(Persistence persistence) {
        int nativePort = TransportDescriptor.getNativeTransportPort();
        int nativePortSSL = TransportDescriptor.getNativeTransportPortSSL();
        InetAddress nativeAddr = TransportDescriptor.getRpcAddress();

        Server.Builder builder = new Server.Builder(persistence)
                .withEventLoopGroup(workerGroup)
                .withHost(nativeAddr);

        if (!TransportDescriptor.getNativeProtocolEncryptionOptions().enabled)
        {
            servers = Collections.singleton(builder.withSSL(false).withPort(nativePort).build());
        }
        else
        {
            if (nativePort != nativePortSSL)
            {
                // user asked for dedicated ssl port for supporting both non-ssl and ssl connections
                servers = Collections.unmodifiableList(
                        Arrays.asList(
                                builder.withSSL(false).withPort(nativePort).build(),
                                builder.withSSL(true).withPort(nativePortSSL).build()
                        )
                );
            }
            else
            {
                // ssl only mode using configured native port
                servers = Collections.singleton(builder.withSSL(true).withPort(nativePort).build());
            }
        }

        ClientMetrics.instance.init(servers);
        servers.forEach(Server::start);
    }

    public static boolean useEpoll()
    {
        final boolean enableEpoll = Boolean.parseBoolean(System.getProperty("stargate.cql.native.epoll.enabled", "true"));

        if (enableEpoll && !Epoll.isAvailable() && NativeLibrary.osType == NativeLibrary.OSType.LINUX)
            logger.warn("epoll not available", Epoll.unavailabilityCause());

        return enableEpoll && Epoll.isAvailable();
    }
}
