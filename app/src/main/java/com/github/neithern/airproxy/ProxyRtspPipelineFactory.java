/*
 * This file is part of AirReceiver.
 *
 * AirReceiver is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * AirReceiver is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with AirReceiver.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.neithern.airproxy;

import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.handler.codec.rtsp.RtspRequestDecoder;
import org.jboss.netty.handler.codec.rtsp.RtspResponseEncoder;
import org.jboss.netty.handler.execution.ExecutionHandler;
import org.phlo.AirReceiver.ExceptionLoggingHandler;
import org.phlo.AirReceiver.HardwareAddressMap;
import org.phlo.AirReceiver.RaopRtspChallengeResponseHandler;
import org.phlo.AirReceiver.RaopRtspHeaderHandler;
import org.phlo.AirReceiver.RaopRtspOptionsHandler;
import org.phlo.AirReceiver.RtspErrorResponseHandler;
import org.phlo.AirReceiver.RtspLoggingHandler;
import org.phlo.AirReceiver.RtspUnsupportedResponseHandler;

import java.net.InetSocketAddress;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;

public class ProxyRtspPipelineFactory implements ChannelPipelineFactory {
    private final Executor m_executor;
    private final ExecutionHandler m_executionHandler;
    private final HardwareAddressMap m_hardwareAddressMap;
    private final ChannelHandler m_closeOnShutdownHandler;
    private final CopyOnWriteArraySet<InetSocketAddress> m_serverSet;

    public ProxyRtspPipelineFactory(CopyOnWriteArraySet<InetSocketAddress> serverSet,
                                    Executor executor,
                                    ExecutionHandler executionHandler,
                                    HardwareAddressMap hardwareAddressMap,
                                    ChannelHandler closeOnShutdownHandler) {
        m_executor = executor;
        m_executionHandler = executionHandler;
        m_hardwareAddressMap = hardwareAddressMap;
        m_closeOnShutdownHandler = closeOnShutdownHandler;
        m_serverSet = serverSet;
    }

    @Override
    public ChannelPipeline getPipeline() throws Exception {
        final ChannelPipeline pipeline = Channels.pipeline();
        pipeline.addLast("executionHandler", m_executionHandler);
        pipeline.addLast("closeOnShutdownHandler", m_closeOnShutdownHandler);
        pipeline.addLast("exceptionLogger", new ExceptionLoggingHandler());
        pipeline.addLast("decoder", new RtspRequestDecoder());
        pipeline.addLast("encoder", new RtspResponseEncoder());
        pipeline.addLast("logger", new RtspLoggingHandler());
        pipeline.addLast("errorResponse", new RtspErrorResponseHandler());
        pipeline.addLast("challengeResponse", new RaopRtspChallengeResponseHandler(m_hardwareAddressMap));
        pipeline.addLast("header", new RaopRtspHeaderHandler());
        pipeline.addLast("options", new RaopRtspOptionsHandler());
        pipeline.addLast("proxy", new ProxyServerHandler(m_executor, m_executionHandler, m_serverSet.toArray(new InetSocketAddress[0])));
        pipeline.addLast("unsupportedResponse", new RtspUnsupportedResponseHandler());
        return pipeline;
    }
}
