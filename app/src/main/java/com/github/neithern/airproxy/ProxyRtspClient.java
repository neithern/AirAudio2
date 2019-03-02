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

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.bootstrap.ConnectionlessBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.FixedReceiveBufferSizePredictorFactory;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.oio.OioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.oio.OioDatagramChannelFactory;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.rtsp.RtspRequestEncoder;
import org.jboss.netty.handler.codec.rtsp.RtspResponseDecoder;
import org.jboss.netty.handler.codec.rtsp.RtspResponseStatuses;
import org.jboss.netty.handler.execution.ExecutionHandler;
import org.phlo.AirReceiver.ExceptionLoggingHandler;
import org.phlo.AirReceiver.RaopRtpDecodeHandler;
import org.phlo.AirReceiver.RaopRtpPacket;
import org.phlo.AirReceiver.RaopRtspMethods;
import org.phlo.AirReceiver.RtpEncodeHandler;
import org.phlo.AirReceiver.Utils;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;

public class ProxyRtspClient implements ChannelPipelineFactory {
    private static Logger s_logger = Logger.getLogger("ProxyRtspClient");

    private static final int RTP_AUDIO_PORT = 56407;
    private static final int RTP_CONTROL_PORT = 56408;
    private static final int RTP_TIMING_PORT = 56409;

    private final ExecutionHandler m_executionHandler;
    private final InetSocketAddress m_remoteAddress;

    private final ChannelHandler m_exceptionLoggingHandler = new ExceptionLoggingHandler();
    private final ChannelHandler m_decodeHandler = new RaopRtpDecodeHandler();
    private final ChannelHandler m_encodeHandler = new RtpEncodeHandler();
    private final ChannelHandler m_receiverHandler = new RtpReceiverHandler();

    private Channel m_rtspChannel;
    private Channel m_rtpAudioChannel;
    private Channel m_rtpControlChannel;
    private Channel m_rtpTimingChannel;

    private Channel m_upRtpControlChannel;
    private Channel m_upRtpTimingChannel;

    public ProxyRtspClient(ExecutionHandler executionHandler, InetSocketAddress remoteAddress) {
        m_executionHandler = executionHandler;
        m_remoteAddress = remoteAddress;

        final ClientBootstrap bootstrap = new ClientBootstrap(new OioClientSocketChannelFactory(m_executionHandler.getExecutor()));
        bootstrap.setOption("tcpNoDelay", true);
        bootstrap.setOption("keepAlive", true);
        bootstrap.setOption("sendBufferSize", 65536);
        bootstrap.setOption("receiveBufferSize", 65536);
        bootstrap.setPipelineFactory(this);

        ChannelFuture future = bootstrap.connect(remoteAddress);
        future.awaitUninterruptibly();
        m_rtspChannel = future.getChannel();
    }

    public void close() {
        s_logger.info("close for: " + m_remoteAddress);

        Channel channel = m_rtpAudioChannel;
        m_rtpAudioChannel = null;
        if (channel != null)
            channel.close();

        channel = m_rtpControlChannel;
        m_rtpControlChannel = null;
        if (channel != null)
            channel.close();

        channel = m_rtpTimingChannel;
        m_rtpTimingChannel = null;
        if (channel != null)
            channel.close();

        channel = m_rtspChannel;
        m_rtspChannel = null;
        if (channel != null)
            channel.close();

        m_upRtpControlChannel = null;
        m_upRtpTimingChannel = null;
    }

    public void setUpStreamRtpChannels(Channel upRtpControlChannel, Channel upRtpTimingChannel) {
        m_upRtpControlChannel = upRtpControlChannel;
        m_upRtpTimingChannel = upRtpTimingChannel;
    }

    public ChannelFuture sendRequest(HttpRequest request) {
        if (RaopRtspMethods.SETUP.equals(request.getMethod())) {
            final DefaultHttpRequest newReq = new DefaultHttpRequest(
                    request.getProtocolVersion(), request.getMethod(), request.getUri());
            for (Map.Entry<String, String> entry : request.getHeaders())
                newReq.setHeader(entry.getKey(), entry.getValue());
            final String[] options = newReq.getHeader(ProxyServerHandler.HeaderTransport).split(";");
            for (int i = 0; i < options.length; i++) {
                final String opt = options[i];
                final Matcher matcher = ProxyServerHandler.s_pattern_transportOption.matcher(opt);
                if (!matcher.matches())
                    continue;
                final String key = matcher.group(1);
                if ("control_port".equals(key))
                    options[i] = key + '=' + Integer.toString(RTP_CONTROL_PORT);
                else if ("timing_port".equals(key))
                    options[i] = key + '=' + Integer.toString(RTP_TIMING_PORT);
            }
            newReq.setHeader(ProxyServerHandler.HeaderTransport, Utils.buildTransportOptions(Arrays.asList(options)));
            request = newReq;
        }
        s_logger.fine("request to " + m_remoteAddress + ": " + request);
        return writeMessage(m_rtspChannel, request);
    }

    public void sendAudioPacket(RaopRtpPacket.Audio packet) {
        writeMessage(m_rtpAudioChannel, packet);
    }

    public void sendSyncPacket(RaopRtpPacket.Sync packet) {
        writeMessage(m_rtpControlChannel, packet);
    }

    public void sendTimingPacket(RaopRtpPacket.Timing packet) {
        writeMessage(m_rtpTimingChannel, packet);
    }

    @Override
    public ChannelPipeline getPipeline() throws Exception {
        final ChannelPipeline pipeline = Channels.pipeline();
        pipeline.addLast("encoder", new RtspRequestEncoder());
        pipeline.addLast("decoder", new RtspResponseDecoder());
        pipeline.addLast("response", new SimpleChannelUpstreamHandler() {
            @Override
            public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
                final Object msg = e.getMessage();
                if (msg instanceof HttpResponse) {
                    final HttpResponse response = (HttpResponse) msg;
                    s_logger.info("response from " + m_remoteAddress + ": " + msg);
                    if (RtspResponseStatuses.OK.equals(response.getStatus())
                            && response.containsHeader(ProxyServerHandler.HeaderTransport))
                        onSetupResponseReceived(ctx, response);
                }
            }
        });
        return pipeline;
    }

    private void onSetupResponseReceived(ChannelHandlerContext ctx, HttpResponse response) throws Exception {
        final InetSocketAddress localAddress = (InetSocketAddress) ctx.getChannel().getLocalAddress();
        final String[] options = response.getHeader(ProxyServerHandler.HeaderTransport).split(";");
        for (String opt : options) {
            final Matcher matcher = ProxyServerHandler.s_pattern_transportOption.matcher(opt);
            if (!matcher.matches())
                continue;
            final String key = matcher.group(1);
            final String value = matcher.group(3);
            if ("server_port".equals(key) || "control_port".equals(key) || "timing_port".equals(key)) {
                final int remotePort = Integer.valueOf(value);
                if ("server_port".equals(key))
                    m_rtpAudioChannel = createRtpChannel(localAddress, RTP_AUDIO_PORT, remotePort);
                else if ("control_port".equals(key))
                    m_rtpControlChannel = createRtpChannel(localAddress, RTP_CONTROL_PORT, remotePort);
                else if ("timing_port".equals(key))
                    m_rtpTimingChannel = createRtpChannel(localAddress, RTP_TIMING_PORT, remotePort);
                s_logger.info("create rtp channel for " + m_remoteAddress + ": " + key);
            }
        }
    }

    private Channel createRtpChannel(InetSocketAddress localAddr, int localPort, int remotePort) throws Exception {
        final ConnectionlessBootstrap bootstrap = new ConnectionlessBootstrap(new OioDatagramChannelFactory(m_executionHandler.getExecutor()));
        bootstrap.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(1500));
        bootstrap.setOption("receiveBufferSize", 1048576);
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            @Override
            public ChannelPipeline getPipeline() throws Exception {
                final ChannelPipeline pipeline = Channels.pipeline();
                pipeline.addLast("executionHandler", m_executionHandler);
                pipeline.addLast("exceptionLogger", m_exceptionLoggingHandler);
                pipeline.addLast("decoder", m_decodeHandler);
                pipeline.addLast("encoder", m_encodeHandler);
                pipeline.addLast("receiver", m_receiverHandler);
                return pipeline;
            }
        });

        Channel channel = bootstrap.bind(Utils.substitutePort(localAddr, localPort));

        final InetSocketAddress remoteAddress = Utils.substitutePort(m_remoteAddress, remotePort);
        channel.connect(remoteAddress);
        return channel;
    }

    private ChannelFuture writeMessage(Channel channel, Object message) {
        try {
            return channel != null ? channel.write(message) : null;
        } catch (RuntimeException e) {
            s_logger.warning("write message failed for: " + m_remoteAddress + ", " + e);
            throw e;
        }
    }

    private class RtpReceiverHandler extends SimpleChannelUpstreamHandler {
        @Override
        public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent evt) throws Exception {
            synchronized (this) {
                final Object message = evt.getMessage();
                if (message instanceof RaopRtpPacket.RetransmitRequest) {
                    writeMessage(m_upRtpControlChannel, message);
                } else if (message instanceof RaopRtpPacket.TimingRequest) {
                    writeMessage(m_upRtpTimingChannel, message);
                }
            }
            super.messageReceived(ctx, evt);
        }
    }
}
