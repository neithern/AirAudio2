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

package org.phlo.AirReceiver;

import org.jboss.netty.channel.*;
import org.jboss.netty.handler.codec.rtsp.*;
import org.jboss.netty.handler.execution.ExecutionHandler;

import java.util.concurrent.ExecutorService;

/**
 * Factory for AirTunes/RAOP RTSP channels
 */
public class RaopRtspPipelineFactory implements ChannelPipelineFactory {
	private final int m_audioStream;
	private final AudioChannel m_channelMode;
	private final ExecutorService m_executor;
	private final ExecutionHandler m_executionHandler;
    private final HardwareAddressMap m_hardwareAddressMap;
	private final ChannelUpstreamHandler m_closeOnShutdownHandler;

	public RaopRtspPipelineFactory(int audioStream, AudioChannel channelMode,
								   ExecutorService executor, ExecutionHandler executionHandler,
                                   HardwareAddressMap hardwareAddressMap,
								   ChannelUpstreamHandler closeOnShutdownHandler) {
		m_audioStream = audioStream;
		m_channelMode = channelMode;
		m_executor = executor;
		m_executionHandler = executionHandler;
        m_hardwareAddressMap = hardwareAddressMap;
		m_closeOnShutdownHandler = closeOnShutdownHandler;
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
		pipeline.addLast("audio", new RaopAudioHandler(m_executor, m_executionHandler, m_audioStream, m_channelMode));
		pipeline.addLast("unsupportedResponse", new RtspUnsupportedResponseHandler());

		return pipeline;
	}

}
