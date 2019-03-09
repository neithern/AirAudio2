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
import org.jboss.netty.buffer.ChannelBuffer;
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
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.oio.OioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.oio.OioDatagramChannelFactory;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.rtsp.RtspRequestEncoder;
import org.jboss.netty.handler.codec.rtsp.RtspResponseDecoder;
import org.jboss.netty.handler.codec.rtsp.RtspResponseStatuses;
import org.phlo.AirReceiver.ExceptionLoggingHandler;
import org.phlo.AirReceiver.RaopRtpDecodeHandler;
import org.phlo.AirReceiver.RaopRtpPacket;
import org.phlo.AirReceiver.RaopRtspMethods;
import org.phlo.AirReceiver.RtpEncodeHandler;
import org.phlo.AirReceiver.RtpPacket;
import org.phlo.AirReceiver.Utils;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Matcher;

public class ProxyRtspClient implements ChannelPipelineFactory {
    private static Logger s_logger = Logger.getLogger("ProxyRtspClient");

    enum RtpChannelType { Audio, Control, Timing }

    private final ProxyServerHandler m_server;
    private final InetSocketAddress m_remoteAddress;
    private final int m_rtpPortFirst;
    private final ChannelHandler m_rtpDecoder = new RaopRtpDecodeHandler();
    private final ChannelHandler m_rtpEncoder = new RtpEncodeHandler();

    private final ChannelGroup m_channels = new DefaultChannelGroup();
    private Channel m_rtspChannel;
    private Channel m_rtpAudioChannel;
    private Channel m_rtpControlChannel;
    private Channel m_rtpTimingChannel;
    private Channel m_upRtpControlChannel;
    private Channel m_upRtpTimingChannel;

    public ProxyRtspClient(ProxyServerHandler server, InetSocketAddress remoteAddress, int portFirst) {
        m_server = server;
        m_remoteAddress = remoteAddress;
        m_rtpPortFirst = portFirst + 1;

        final ClientBootstrap bootstrap = new ClientBootstrap(new OioClientSocketChannelFactory(Executors.newSingleThreadExecutor()));
        bootstrap.setOption("tcpNoDelay", true);
        bootstrap.setOption("keepAlive", true);
        bootstrap.setPipelineFactory(this);

        m_rtspChannel = bootstrap.connect(remoteAddress).getChannel();
        synchronized (m_channels) {
            m_channels.add(m_rtspChannel);
        }
        s_logger.info("Create for: " + m_remoteAddress);
    }

    public void close() {
        synchronized (m_channels) {
            m_channels.close().awaitUninterruptibly();
            m_channels.clear();
        }

        m_rtspChannel = null;
        m_rtpAudioChannel = null;
        m_rtpControlChannel = null;
        m_rtpTimingChannel = null;

        m_upRtpControlChannel = null;
        m_upRtpTimingChannel = null;

        s_logger.info("close for: " + m_remoteAddress);
    }

    public void setUpStreamRtpChannels(Channel upRtpControlChannel, Channel upRtpTimingChannel) {
        m_upRtpControlChannel = upRtpControlChannel;
        m_upRtpTimingChannel = upRtpTimingChannel;
    }

    public ChannelFuture sendRequest(HttpRequest request) {
        final HttpRequest newReq = copyRequest(request);
        if (RaopRtspMethods.SETUP.equals(request.getMethod())) {
            final String[] options = newReq.headers().get(ProxyServerHandler.HeaderTransport).split(";");
            for (int i = 0; i < options.length; i++) {
                final String opt = options[i];
                final Matcher matcher = ProxyServerHandler.s_pattern_transportOption.matcher(opt);
                if (!matcher.matches())
                    continue;
                final String key = matcher.group(1);
                if ("control_port".equals(key)) {
                    options[i] = key + "=" + (m_rtpPortFirst + RtpChannelType.Control.ordinal());
                } else if ("timing_port".equals(key)) {
                    options[i] = key + "=" + (m_rtpPortFirst + RtpChannelType.Timing.ordinal());
                }
            }
            newReq.headers().set(ProxyServerHandler.HeaderTransport, Utils.buildTransportOptions(Arrays.asList(options)));
        }
        s_logger.info("send request to " + m_remoteAddress + ": " + newReq);
        return writeMessage(m_rtspChannel, newReq);
    }

    public ChannelFuture sendAudioPacket(Object packet) {
        return writeMessage(m_rtpAudioChannel, packet);
    }

    public ChannelFuture sendControlPacket(Object packet) {
        return writeMessage(m_rtpControlChannel, packet);
    }

    @Override
    public ChannelPipeline getPipeline() throws Exception {
        final ChannelPipeline pipeline = Channels.pipeline();
        pipeline.addLast("exceptionLogger", new ExceptionLoggingHandler());
        pipeline.addLast("encoder", new RtspRequestEncoder());
        pipeline.addLast("decoder", new RtspResponseDecoder());
        pipeline.addLast("response", new RtspResponseHandler());
        return pipeline;
    }

    private Channel createRtpChannel(InetSocketAddress localAddress, int remotePort, final RtpChannelType channelType) {
        final OioDatagramChannelFactory channelFactory = new OioDatagramChannelFactory(m_server.m_executor);
        final ConnectionlessBootstrap bootstrap = new ConnectionlessBootstrap(channelFactory);
        bootstrap.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(1500));
        bootstrap.setOption("receiveBufferSize", 1048576);
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            @Override
            public ChannelPipeline getPipeline() throws Exception {
                final ChannelPipeline pipeline = Channels.pipeline();
                pipeline.addLast("executionHandler", m_server.m_executionHandler);
                pipeline.addLast("receiverHandler", new RtpReceiverHandler(channelType));
                return pipeline;
            }
        });

        Channel channel = bootstrap.bind(Utils.substitutePort(localAddress, m_rtpPortFirst + channelType.ordinal()));
        channel.connect(Utils.substitutePort(m_remoteAddress, remotePort));
        s_logger.info("create rtp channel " + channel.getLocalAddress() + " for " + m_remoteAddress + ": " + channelType);
        synchronized (m_channels) {
            m_channels.add(channel);
        }
        return channel;
    }

    private void onSetupResponseReceived(ChannelHandlerContext ctx, HttpResponse response) {
        final InetSocketAddress localAddress = (InetSocketAddress) ctx.getChannel().getLocalAddress();
        final String[] options = response.headers().get(ProxyServerHandler.HeaderTransport).split(";");
        for (String opt : options) {
            final Matcher matcher = ProxyServerHandler.s_pattern_transportOption.matcher(opt);
            if (!matcher.matches())
                continue;
            final String key = matcher.group(1);
            final String value = matcher.group(3);
            if ("server_port".equals(key) || "control_port".equals(key) || "timing_port".equals(key)) {
                final int remotePort = Integer.valueOf(value);
                if ("server_port".equals(key))
                    m_rtpAudioChannel = createRtpChannel(localAddress, remotePort, RtpChannelType.Audio);
                else if ("control_port".equals(key))
                    m_rtpControlChannel = createRtpChannel(localAddress, remotePort, RtpChannelType.Control);
                else if ("timing_port".equals(key))
                    m_rtpTimingChannel = createRtpChannel(localAddress, remotePort, RtpChannelType.Timing);
            }
        }
    }

    private ChannelFuture writeMessage(Channel channel, Object message) {
        try {
            return channel != null ? channel.write(message) : null;
        } catch (RuntimeException e) {
            s_logger.warning("write message failed for: " + m_remoteAddress + ", " + e);
            throw e;
        }
    }

    private final AtomicInteger m_lastCSeq = new AtomicInteger();

    public int getLastCSeq() {
        return m_lastCSeq.get();
    }

    private class RtspResponseHandler extends SimpleChannelUpstreamHandler {
        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
            final Object msg = e.getMessage();
            if (msg instanceof HttpResponse) {
                final HttpResponse response = (HttpResponse) msg;
                m_lastCSeq.set(Integer.valueOf(response.headers().get("CSeq")));
                s_logger.info("receive response from " + m_remoteAddress + ": " + msg);
                if (RtspResponseStatuses.OK.equals(response.getStatus())
                        && response.headers().contains(ProxyServerHandler.HeaderTransport))
                    onSetupResponseReceived(ctx, response);
            }
        }
    }

    private class RtpReceiverHandler extends SimpleChannelUpstreamHandler {
        private final RtpChannelType m_type;

        public RtpReceiverHandler(RtpChannelType type) {
            m_type = type;
        }

        @Override
        public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent evt) throws Exception {
            final ChannelBuffer buffer = (ChannelBuffer) evt.getMessage();
            if (m_type == RtpChannelType.Control) {
                writeMessage(m_upRtpControlChannel, buffer);
            } else if (m_type == RtpChannelType.Timing) {
                final Channel channel = m_rtpTimingChannel;
                if (channel != null && RtpPacket.getPayloadType(buffer) == RaopRtpPacket.TimingRequest.PayloadType) {
                    final long key = RaopRtpPacket.Timing.getSendTime(buffer).getAsLong();
                    m_server.m_timingChannelMap.put(key, channel);
                }
                writeMessage(m_upRtpTimingChannel, buffer);
            } else {
                super.messageReceived(ctx, evt);
            }
        }
    }

    private static HttpRequest copyRequest(HttpRequest req) {
        final HttpRequest newReq = new DefaultHttpRequest(req.getProtocolVersion(), req.getMethod(), req.getUri());
        newReq.headers().add(req.headers());
        newReq.setContent(req.getContent());
        return newReq;
    }
}
