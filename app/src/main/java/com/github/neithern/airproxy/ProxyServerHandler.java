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

import org.jboss.netty.bootstrap.ConnectionlessBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.FixedReceiveBufferSizePredictorFactory;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.oio.OioDatagramChannelFactory;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.rtsp.RtspResponseStatuses;
import org.jboss.netty.handler.codec.rtsp.RtspVersions;
import org.jboss.netty.handler.execution.ExecutionHandler;
import org.phlo.AirReceiver.AudioOutputQueue;
import org.phlo.AirReceiver.ExceptionLoggingHandler;
import org.phlo.AirReceiver.ProtocolException;
import org.phlo.AirReceiver.RaopRtpAudioAlacDecodeHandler;
import org.phlo.AirReceiver.RaopRtpAudioDecryptionHandler;
import org.phlo.AirReceiver.RaopRtpDecodeHandler;
import org.phlo.AirReceiver.RaopRtpPacket;
import org.phlo.AirReceiver.RaopRtpRetransmitRequestHandler;
import org.phlo.AirReceiver.RaopRtpTimingHandler;
import org.phlo.AirReceiver.RaopRtspMethods;
import org.phlo.AirReceiver.RtpEncodeHandler;
import org.phlo.AirReceiver.Utils;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

/**
 * Handles the configuration, creation and destruction of RTP channels.
 */
public class ProxyServerHandler extends SimpleChannelUpstreamHandler {
	private static Logger s_logger = Logger.getLogger("RaopAudioHandler");

	/**
	 * The RTP channel type
	 */
	enum RaopRtpChannelType { Audio, Control, Timing };

	private static final int RTP_AUDIO_PORT = 56400;
	private static final int RTP_CONTROL_PORT = 56401;
	private static final int RTP_TIMING_PORT = 56402;

	protected static final String HeaderTransport = "Transport";
	protected static final String HeaderSession = "Session";

	private class RtpAudioReceiverHandler extends SimpleChannelUpstreamHandler {
		@Override
		public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent evt) throws Exception {
            final Object message = evt.getMessage();
			if (message instanceof RaopRtpPacket.Audio) {
				final RaopRtpPacket.Audio packet = (RaopRtpPacket.Audio) message;
				for (ProxyRtspClient client : m_rtspClients)
					client.sendAudioPacket(packet);
			}
			super.messageReceived(ctx, evt);
		}
	}

	private class RtpControlAndTimingHandler extends SimpleChannelUpstreamHandler {
		@Override
		public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent evt) throws Exception {
			final Object message = evt.getMessage();
			if (message instanceof RaopRtpPacket.Sync) {
				final RaopRtpPacket.Sync packet = (RaopRtpPacket.Sync) message;
				for (ProxyRtspClient client : m_rtspClients)
					client.sendSyncPacket(packet);
			} else if (message instanceof RaopRtpPacket.TimingResponse) {
				final RaopRtpPacket.TimingResponse packet = (RaopRtpPacket.TimingResponse) message;
				for (ProxyRtspClient client : m_rtspClients)
					client.sendTimingPacket(packet);
			}
			super.messageReceived(ctx, evt);
		}
	}

	/**
	 * Executor service used for the RTP channels
	 */
	private final ExecutionHandler m_executionHandler;

	private final ChannelHandler m_exceptionLoggingHandler = new ExceptionLoggingHandler();
	private final ChannelHandler m_decodeHandler = new RaopRtpDecodeHandler();
	private final ChannelHandler m_encodeHandler = new RtpEncodeHandler();
	//private final ChannelHandler m_packetLoggingHandler = new RtpLoggingHandler();
	private final ChannelHandler m_audioReceiveHandler = new RtpAudioReceiverHandler();
	private final ChannelHandler m_controlAndTimingHandler = new RtpControlAndTimingHandler();

	private final InetSocketAddress[] m_rtspServerAddresses;

	/**
	 * All RTP channels belonging to this RTSP connection
	 */
	private final ChannelGroup m_serverRtpChannels = new DefaultChannelGroup();
	private Channel m_serverRtpControlChannel;
	private Channel m_serverRtpTimingChannel;

	private final CopyOnWriteArraySet<ProxyRtspClient> m_rtspClients = new CopyOnWriteArraySet<>();

	/**
	 * Creates an instance, using the ExecutorService for the RTP channel's datagram socket factory
	 * @param executionHandler
	 */
	public ProxyServerHandler(ExecutionHandler executionHandler, InetSocketAddress[] rtspServerAddresses) {
		m_executionHandler = executionHandler;
		m_rtspServerAddresses = rtspServerAddresses;
	}

	private void reset() {
		synchronized (m_serverRtpChannels) {
			m_serverRtpChannels.close();
			m_serverRtpChannels.clear();
		}

		for (ProxyRtspClient client : m_rtspClients)
			client.close();
		m_rtspClients.clear();
	}

	private void createRtspClients() {
		for (InetSocketAddress address : m_rtspServerAddresses) {
			try {
				final ProxyRtspClient client = new ProxyRtspClient(m_executionHandler, address);
				m_rtspClients.add(client);
			} catch (Exception e) {
				s_logger.warning("create rtsp client for " + address + " failed: " + e);
			}
		}
	}

	private void forwardRtspRequest(final HttpRequest req) {
		final ArrayList<ChannelFuture> futures = new ArrayList<>();
		for (ProxyRtspClient client : m_rtspClients) {
			ChannelFuture future = client.sendRequest(req);
			if (future != null)
				futures.add(future);
		}
		for (ChannelFuture future : futures) {
			future.awaitUninterruptibly();
		}
	}

	@Override
	public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
		super.channelOpen(ctx, e);
	}

	@Override
	public void channelClosed(final ChannelHandlerContext ctx, final ChannelStateEvent evt) throws Exception {
		s_logger.info("RTSP connection was shut down, closing RTP channels and audio output queue");
		reset();
		super.channelClosed(ctx, evt);
	}

	@Override
	public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent evt) throws Exception {
		final HttpRequest req = (HttpRequest) evt.getMessage();
		final HttpMethod method = req.getMethod();

		if (RaopRtspMethods.ANNOUNCE.equals(method)) {
			announceReceived(ctx, req);
			forwardRtspRequest(req);
		} else if (RaopRtspMethods.SETUP.equals(method)) {
			setupReceived(ctx, req);
			forwardRtspRequest(req);
		} else if (RaopRtspMethods.RECORD.equals(method)) {
			recordReceived(ctx, req);
			forwardRtspRequest(req);
		} else if (RaopRtspMethods.FLUSH.equals(method)) {
			flushReceived(ctx, req);
			forwardRtspRequest(req);
		} else if (RaopRtspMethods.TEARDOWN.equals(method)) {
			teardownReceived(ctx, req);
			forwardRtspRequest(req);
		} else if (RaopRtspMethods.SET_PARAMETER.equals(method)) {
			setParameterReceived(ctx, req);
			forwardRtspRequest(req);
		} else if (RaopRtspMethods.GET_PARAMETER.equals(method)) {
			getParameterReceived(ctx, req);
		} else {
			super.messageReceived(ctx, evt);
		}
	}

	/**
	 * SDP line. Format is
	 * <br>
	 * {@code
	 * <attribute>=<value>
	 * }
	 */
	private static Pattern s_pattern_sdp_line = Pattern.compile("^([a-z])=(.*)$");

	/**
	 * SDP attribute {@code m}. Format is
	 * <br>
	 * {@code
	 * <media> <port> <transport> <formats>
	 * }
	 * <p>
	 * RAOP/AirTunes always required {@code <media>=audio, <transport>=RTP/AVP}
	 * and only a single format is allowed. The port is ignored.
	 */
	private static Pattern s_pattern_sdp_m = Pattern.compile("^audio ([^ ]+) RTP/AVP ([0-9]+)$");

	/**
	 * SDP attribute {@code a}. Format is
	 * <br>
	 * {@code <flag>}
	 * <br>
	 * or
	 * <br>
	 * {@code <attribute>:<value>}
	 * <p>
	 * RAOP/AirTunes uses only the second case, with the attributes
	 * <ul>
	 * <li> {@code <attribute>=rtpmap}
	 * <li> {@code <attribute>=fmtp}
	 * <li> {@code <attribute>=rsaaeskey}
	 * <li> {@code <attribute>=aesiv}
	 * <li> {@code <attribute>=min-latency}
	 * </ul>
	 */
	private static Pattern s_pattern_sdp_a = Pattern.compile("^(\\w+):?(.*)$"); //changed to support dash in the attribute name

	/**
	 * SDP {@code a} attribute {@code rtpmap}. Format is
	 * <br>
	 * {@code <format> <encoding>}
	 * for RAOP/AirTunes instead of {@code <format> <encoding>/<clock rate>}.
	 * <p>
	 * RAOP/AirTunes always uses encoding {@code AppleLossless}
	 */
	private static Pattern s_pattern_sdp_a_rtpmap = Pattern.compile("^([0-9]+) (.*)$");

	private static Charset s_ascii_charset = Charset.forName("ASCII");

	/**
	 * Handles ANNOUNCE requests and creates an {@link AudioOutputQueue} and
	 * the following handlers for RTP channels
	 * <ul>
	 * <li>{@link RaopRtpTimingHandler}
	 * <li>{@link RaopRtpRetransmitRequestHandler}
	 * <li>{@link RaopRtpAudioDecryptionHandler}
	 * <li>{@link RaopRtpAudioAlacDecodeHandler}
	 * </ul>
	 */
	public synchronized void announceReceived(final ChannelHandlerContext ctx, final HttpRequest req)
		throws Exception
	{
		/* ANNOUNCE must contain stream information in SDP format */
		if (!req.headers().contains("Content-Type"))
			throw new ProtocolException("No Content-Type header");
		final String contentType = req.headers().get("Content-Type");
		if (!"application/sdp".equals(contentType))
			throw new ProtocolException("Invalid Content-Type header, expected application/sdp but got " + contentType);

		reset();
		createRtspClients();

		/* Get SDP stream information */
		final String dsp = req.getContent().toString(s_ascii_charset).replace("\r", "");

		SecretKey aesKey = null;
		IvParameterSpec aesIv = null;
		int alacFormatIndex = -1;
		int audioFormatIndex = -1;
		int descriptionFormatIndex = -1;
		String[] formatOptions = null;

		for(final String line: dsp.split("\n")) {
			/* Split SDP line into attribute and setting */
			final Matcher line_matcher = s_pattern_sdp_line.matcher(line);
			if (!line_matcher.matches())
				throw new ProtocolException("Cannot parse SDP line " + line);
			final char attribute = line_matcher.group(1).charAt(0);
			final String setting = line_matcher.group(2);

			/* Handle attributes */
			switch (attribute) {
				case 'm':
					/* Attribute m. Maps an audio format index to a stream */
					final Matcher m_matcher = s_pattern_sdp_m.matcher(setting);
					if (!m_matcher.matches())
						throw new ProtocolException("Cannot parse SDP " + attribute + "'s setting " + setting);
					audioFormatIndex = Integer.valueOf(m_matcher.group(2));
					break;

				case 'a':
					/* Attribute a. Defines various session properties */
					final Matcher a_matcher = s_pattern_sdp_a.matcher(setting);
					if (!a_matcher.matches())
						throw new ProtocolException("Cannot parse SDP " + attribute + "'s setting " + setting);
					final String key = a_matcher.group(1);
					final String value = a_matcher.group(2);

					if ("rtpmap".equals(key)) {
						/* Sets the decoder for an audio format index */
						final Matcher a_rtpmap_matcher = s_pattern_sdp_a_rtpmap.matcher(value);
						if (!a_rtpmap_matcher.matches())
							throw new ProtocolException("Cannot parse SDP " + attribute + "'s rtpmap entry " + value);

						final int formatIdx = Integer.valueOf(a_rtpmap_matcher.group(1));
						final String format = a_rtpmap_matcher.group(2);
						if ("AppleLossless".equals(format))
							alacFormatIndex = formatIdx;
					}
					else if ("fmtp".equals(key)) {
						/* Sets the decoding parameters for a audio format index */
						final String[] parts = value.split(" ");
						if (parts.length > 0)
							descriptionFormatIndex = Integer.valueOf(parts[0]);
						if (parts.length > 1)
							formatOptions = Arrays.copyOfRange(parts, 1, parts.length);
					}
					else if ("rsaaeskey".equals(key)) {
						/* Sets the AES key required to decrypt the audio data. The key is
						 * encrypted wih the AirTunes private key
						 */
					}
					else if ("aesiv".equals(key)) {
						/* Sets the AES initialization vector */
					}
					break;
			}
		}

		/* Validate SDP information */

		/* The format index of the stream must match the format index from the rtpmap attribute */
		if (alacFormatIndex != audioFormatIndex)
			throw new ProtocolException("Audio format " + audioFormatIndex + " not supported");

		/* The format index from the rtpmap attribute must match the format index from the fmtp attribute */
		if (audioFormatIndex != descriptionFormatIndex)
			throw new ProtocolException("Auido format " + audioFormatIndex + " lacks fmtp line");

		/* The fmtp attribute must have contained format options */
		if (formatOptions == null)
			throw new ProtocolException("Auido format " + audioFormatIndex + " incomplete, format options not set");

		final HttpResponse response = new DefaultHttpResponse(RtspVersions.RTSP_1_0,  RtspResponseStatuses.OK);
		ctx.getChannel().write(response);
	}

	/**
	 * {@code Transport} header option format. Format of a single option is
	 * <br>
	 * {@code <name>=<value>}
	 * <br>
	 * format of the {@code Transport} header is
	 * <br>
	 * {@code <protocol>;<name1>=<value1>;<name2>=<value2>;...}
	 * <p>
	 * For RAOP/AirTunes, {@code <protocol>} is always {@code RTP/AVP/UDP}.
	 */
	public static Pattern s_pattern_transportOption = Pattern.compile("^([A-Za-z0-9_-]+)(=(.*))?$");

	/**
	 * Handles SETUP requests and creates the audio, control and timing RTP channels
	 */
	public synchronized void setupReceived(final ChannelHandlerContext ctx, final HttpRequest req)
		throws ProtocolException
	{
		/* Request must contain a Transport header */
		if (!req.headers().contains(HeaderTransport))
			throw new ProtocolException("No Transport header");

		/* Split Transport header into individual options and prepare reponse options list */
		final Deque<String> requestOptions = new LinkedList<String>(Arrays.asList(req.headers().get(HeaderTransport).split(";")));
		final List<String> responseOptions = new LinkedList<String>();

		/* Transport header. Protocol must be RTP/AVP/UDP */
		final String requestProtocol = requestOptions.removeFirst();
		if (!"RTP/AVP/UDP".equals(requestProtocol))
			throw new ProtocolException("Transport protocol must be RTP/AVP/UDP, but was " + requestProtocol);
		responseOptions.add(requestProtocol);

		final Channel ctxChannel = ctx.getChannel();
		final InetSocketAddress localAddress = (InetSocketAddress) ctxChannel.getLocalAddress();
		final InetSocketAddress remoteAddress = (InetSocketAddress) ctxChannel.getRemoteAddress();

		/* Parse incoming transport options and build response options */
		for(final String requestOption: requestOptions) {
			/* Split option into key and value */
			final Matcher m_transportOption = s_pattern_transportOption.matcher(requestOption);
			if (!m_transportOption.matches())
				throw new ProtocolException("Cannot parse Transport option " + requestOption);
			final String key = m_transportOption.group(1);
			final String value = m_transportOption.group(3);

			if ("interleaved".equals(key)) {
				/* Probably means that two channels are interleaved in the stream. Included in the response options */
				if (!"0-1".equals(value))
					throw new ProtocolException("Unsupported Transport option, interleaved must be 0-1 but was " + value);
				responseOptions.add("interleaved=0-1");
			}
			else if ("mode".equals(key)) {
				/* Means the we're supposed to receive audio data, not send it. Included in the response options */
				if (!"record".equals(value))
					throw new ProtocolException("Unsupported Transport option, mode must be record but was " + value);
				responseOptions.add("mode=record");
			}
			else if ("control_port".equals(key)) {
				/* Port number of the client's control socket. Response includes port number of *our* control port */
				final int clientControlPort = Integer.valueOf(value);
				final Channel channel = createRtpChannel(
					Utils.substitutePort(localAddress, RTP_CONTROL_PORT),
					Utils.substitutePort(remoteAddress, clientControlPort),
					RaopRtpChannelType.Control
				);
				m_serverRtpControlChannel = channel;
				s_logger.info("Launched RTP control service on " + channel.getLocalAddress());
				responseOptions.add("control_port=" + ((InetSocketAddress) channel.getLocalAddress()).getPort());
			}
			else if ("timing_port".equals(key)) {
				/* Port number of the client's timing socket. Response includes port number of *our* timing port */
				final int clientTimingPort = Integer.valueOf(value);
				final Channel channel = createRtpChannel(
					Utils.substitutePort(localAddress, RTP_TIMING_PORT),
					Utils.substitutePort(remoteAddress, clientTimingPort),
					RaopRtpChannelType.Timing
				);
				m_serverRtpTimingChannel = channel;
				s_logger.info("Launched RTP timing service on " + channel.getLocalAddress());
				responseOptions.add("timing_port=" + ((InetSocketAddress) channel.getLocalAddress()).getPort());
			}
			else {
				/* Ignore unknown options */
				responseOptions.add(requestOption);
			}
		}

		/* Create audio socket and include it's port in our response */
		final Channel channel = createRtpChannel(
			Utils.substitutePort(localAddress, RTP_AUDIO_PORT),
			null,
			RaopRtpChannelType.Audio
		);
		s_logger.info("Launched RTP audio service on " + channel.getLocalAddress());
		responseOptions.add("server_port=" + ((InetSocketAddress) channel.getLocalAddress()).getPort());

		for (ProxyRtspClient client : m_rtspClients)
			client.setUpStreamRtpChannels(m_serverRtpControlChannel, m_serverRtpTimingChannel);

		/* Send response */
		final HttpResponse response = new DefaultHttpResponse(RtspVersions.RTSP_1_0,  RtspResponseStatuses.OK);
		response.headers().add(HeaderTransport, Utils.buildTransportOptions(responseOptions));
		response.headers().add(HeaderSession, "DEADBEEEF");
		ctxChannel.write(response);
	}

	/**
	 * Handles RECORD request. We did all the work during ANNOUNCE and SETUP, so there's nothing
	 * more to do.
	 *
	 * iTunes reports the initial RTP sequence and playback time here, which would actually be
	 * helpful. But iOS doesn't, so we ignore it all together.
	 */
	public synchronized void recordReceived(final ChannelHandlerContext ctx, final HttpRequest req) {
		s_logger.info("Client started streaming");

		final HttpResponse response = new DefaultHttpResponse(RtspVersions.RTSP_1_0,  RtspResponseStatuses.OK);
		ctx.getChannel().write(response);
	}

	/**
	 * Handle FLUSH requests.
	 *
	 * iTunes reports the last RTP sequence and playback time here, which would actually be
	 * helpful. But iOS doesn't, so we ignore it all together.
	 */
	private synchronized void flushReceived(final ChannelHandlerContext ctx, final HttpRequest req) {
		s_logger.info("Client paused streaming, flushed audio output queue");

		final HttpResponse response = new DefaultHttpResponse(RtspVersions.RTSP_1_0,  RtspResponseStatuses.OK);
		ctx.getChannel().write(response);
	}

	/**
	 * Handle TEARDOWN requests.
	 */
	private synchronized void teardownReceived(final ChannelHandlerContext ctx, final HttpRequest req) {
		final HttpResponse response = new DefaultHttpResponse(RtspVersions.RTSP_1_0,  RtspResponseStatuses.OK);
		ctx.getChannel().setReadable(false);
		ctx.getChannel().write(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(final ChannelFuture future) throws Exception {
				future.getChannel().close();
				s_logger.info("RTSP connection closed after client initiated teardown");
			}
		});
	}

	/**
	 * SET_PARAMETER syntax. Format is
	 * <br>
	 * {@code <parameter>: <value>}
	 * <p>
	 */
	private static Pattern s_pattern_parameter = Pattern.compile("^([A-Za-z0-9_-]+): *(.*)$");

	private String m_volumeValue;

	/**
	 * Handle SET_PARAMETER request. Currently only {@code volume} is supported
	 */
	public synchronized void setParameterReceived(final ChannelHandlerContext ctx, final HttpRequest req) {
		/* Body in ASCII encoding with unix newlines */
		final String body = req.getContent().toString(s_ascii_charset).replace("\r", "");

		/* Handle parameters */
		for (final String line: body.split("\n")) {
			/* Split parameter into name and value */
			final Matcher m_parameter = s_pattern_parameter.matcher(line);
			if (m_parameter.matches()) {
				final String name = m_parameter.group(1);
				final String value = m_parameter.group(2);
				if ("volume".equals(name))
					m_volumeValue = value;
				s_logger.info("RTSP parameter received: " + name + " = " + value);
			}
		}

		final HttpResponse response = new DefaultHttpResponse(RtspVersions.RTSP_1_0,  RtspResponseStatuses.OK);
		ctx.getChannel().write(response);
	}

	/**
	 * Handle GET_PARAMETER request. Currently only {@code volume} is supported
	 */
	public synchronized void getParameterReceived(final ChannelHandlerContext ctx, final HttpRequest req) {
		final StringBuilder body = new StringBuilder();
		final String volume = m_volumeValue;
		if (volume != null) {
			body.append("volume: ");
			body.append(volume);
			body.append("\r\n");
		}

		final HttpResponse response = new DefaultHttpResponse(RtspVersions.RTSP_1_0,  RtspResponseStatuses.OK);
		ctx.getChannel().write(response);
	}

	/**
	 * Creates an UDP socket and handler pipeline for RTP channels
	 * 
	 * @param local local end-point address
	 * @param remote remote end-point address
	 * @param channelType channel type. Determines which handlers are put into the pipeline
	 * @return open data-gram channel
	 */
	private Channel createRtpChannel(final SocketAddress local, final SocketAddress remote, final RaopRtpChannelType channelType)
	{
		final ConnectionlessBootstrap bootstrap = new ConnectionlessBootstrap(new OioDatagramChannelFactory(m_executionHandler.getExecutor()));
		bootstrap.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(1500));
		bootstrap.setOption("receiveBufferSize", 1024*1024);
		bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
			@Override
			public ChannelPipeline getPipeline() throws Exception {
				final ChannelPipeline pipeline = Channels.pipeline();
				pipeline.addLast("executionHandler", m_executionHandler);
				pipeline.addLast("exceptionLogger", m_exceptionLoggingHandler);
				pipeline.addLast("decoder", m_decodeHandler);
				pipeline.addLast("encoder", m_encodeHandler);
				//pipeline.addLast("packetLogger", m_packetLoggingHandler);
				if (channelType.equals(RaopRtpChannelType.Audio))
					pipeline.addLast("audioReceiver", m_audioReceiveHandler);
				else
					pipeline.addLast("controlAndTiming", m_controlAndTimingHandler);
				return pipeline;
			}
		});

		Channel channel = bootstrap.bind(local);
		if (remote != null)
			channel.connect(remote);
		synchronized (m_serverRtpChannels) {
			m_serverRtpChannels.add(channel);
		}
		return channel;
	}
}
