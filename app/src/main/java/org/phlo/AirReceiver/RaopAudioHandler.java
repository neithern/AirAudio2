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

import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.*;

import javax.crypto.*;
import javax.crypto.spec.*;

import org.jboss.netty.bootstrap.ConnectionlessBootstrap;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.group.*;
import org.jboss.netty.channel.socket.oio.OioDatagramChannelFactory;
import org.jboss.netty.handler.codec.http.*;
import org.jboss.netty.handler.codec.rtsp.*;
import org.jboss.netty.handler.execution.ExecutionHandler;

/**
 * Handles the configuration, creation and destruction of RTP channels.
 */
public class RaopAudioHandler extends SimpleChannelUpstreamHandler {
	private static Logger s_logger = Logger.getLogger("RaopAudioHandler");

	/**
	 * The RTP channel type
	 */
	private enum RaopRtpChannelType { Audio, Control, Timing };

	private static final int RTP_AUDIO_PORT = 56300;
	private static final int RTP_CONTROL_PORT = 56301;
	private static final int RTP_TIMING_PORT = 56302;

	private static final String HeaderTransport = "Transport";
	private static final String HeaderSession = "Session";

	/**
	 * Places incoming audio data on the audio output queue
	 *
	 */
	public class RaopRtpAudioEnqueueHandler extends SimpleChannelUpstreamHandler {
		@Override
		public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent evt)
			throws Exception
		{
			final Object message = evt.getMessage();
			if (message instanceof RaopRtpPacket.Audio) {
				final RaopRtpPacket.Audio audioPacket = (RaopRtpPacket.Audio) message;

				/* Get audio output queue from the enclosing RaopAudioHandler */
				AudioOutputQueue audioOutputQueue = m_audioOutputQueue;
				if (audioOutputQueue != null) {
					audioOutputQueue.enqueue(audioPacket.getTimeStamp(), audioPacket.getPayload());
					if (s_logger.isLoggable(Level.FINEST))
						s_logger.finest("Packet with sequence " + audioPacket.getSequence() + " for playback at " + audioPacket.getTimeStamp() + " submitted to audio output queue");
				} else {
					s_logger.warning("No audio queue available, dropping packet");
				}
			}
			super.messageReceived(ctx, evt);
		}
	}

	/**
	 * Executor service used for the RTP channels
	 */
	private final ExecutorService m_executorService;
	private final ExecutionHandler m_executionHandler;

	private final ChannelHandler m_exceptionLoggingHandler = new ExceptionLoggingHandler();
	private final ChannelHandler m_decodeHandler = new RaopRtpDecodeHandler();
	private final ChannelHandler m_encodeHandler = new RtpEncodeHandler();
    private final ChannelHandler m_enqueueHandler = new RaopRtpAudioEnqueueHandler();

	private ChannelHandler m_audioDecryptHandler;
	private ChannelHandler m_audioDecodeHandler;
	private ChannelHandler m_resendRequestHandler;
	private RaopRtpTimingHandler m_timingHandler;

	private AudioOutputQueue m_audioOutputQueue;

	/**
	 * All RTP channels belonging to this RTSP connection
	 */
	private final ChannelGroup m_rtpChannels = new DefaultChannelGroup();

	private final int m_audioStream;
	private final AudioChannel m_channelMode;

	/**
	 * Creates an instance, using the ExecutorService for the RTP channel's datagram socket factory
	 * @param executorService
	 * @param executionHandler
	 */
	public RaopAudioHandler(final ExecutorService executorService, ExecutionHandler executionHandler, int audioStream, AudioChannel channelMode) {
		m_executorService = executorService;
		m_executionHandler = executionHandler;
		m_audioStream = audioStream;
		m_channelMode = channelMode;
		reset();
	}

	/**
	 * Resets stream-related data (i.e. undoes the effect of ANNOUNCE, SETUP and RECORD
	 */
	private void reset() {
		AudioOutputQueue audioOutputQueue = m_audioOutputQueue;
		m_audioOutputQueue = null;
		if (audioOutputQueue != null)
			audioOutputQueue.close();

		m_rtpChannels.close();
		m_rtpChannels.clear();

		m_audioDecryptHandler = null;
		m_audioDecodeHandler = null;
		m_resendRequestHandler = null;
		m_timingHandler = null;
	}

	@Override
	public void channelClosed(final ChannelHandlerContext ctx, final ChannelStateEvent evt)
		throws Exception
	{
		s_logger.info("RTSP connection was shut down, closing RTP channels and audio output queue");

		reset();

		super.channelClosed(ctx, evt);
	}

	@Override
	public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent evt) throws Exception {
		final HttpRequest req = (HttpRequest)evt.getMessage();
		final HttpMethod method = req.getMethod();

		if (RaopRtspMethods.ANNOUNCE.equals(method)) {
			announceReceived(ctx, req);
		} else if (RaopRtspMethods.SETUP.equals(method)) {
			setupReceived(ctx, req);
		} else if (RaopRtspMethods.RECORD.equals(method)) {
			recordReceived(ctx, req);
		} else if (RaopRtspMethods.FLUSH.equals(method)) {
			flushReceived(ctx, req);
		} else if (RaopRtspMethods.TEARDOWN.equals(method)) {
			teardownReceived(ctx, req);
		} else if (RaopRtspMethods.SET_PARAMETER.equals(method)) {
			setParameterReceived(ctx, req);
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

	private static Charset s_utf8_charset = Charset.forName("UTF-8");

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
		if (!req.containsHeader("Content-Type"))
			throw new ProtocolException("No Content-Type header");
		if (!"application/sdp".equals(req.getHeader("Content-Type")))
			throw new ProtocolException("Invalid Content-Type header, expected application/sdp but got " + req.getHeader("Content-Type"));

		reset();

		/* Get SDP stream information */
		final String dsp = req.getContent().toString(s_utf8_charset).replace("\r", "");

		SecretKey aesKey = null;
		IvParameterSpec aesIv = null;
		int alacFormatIndex = -1;
		int audioFormatIndex = -1;
		int descriptionFormatIndex = -1;
		String[] formatOptions = null;

		for (final String line: dsp.split("\n")) {
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
						final Cipher rsaPkCS1OaepCipher = AirTunesCrytography.getCipher("RSA/None/OAEPWithSHA1AndMGF1Padding");
						rsaPkCS1OaepCipher.init(Cipher.DECRYPT_MODE, AirTunesCrytography.PrivateKey);
						byte[] aesKeyRaw = rsaPkCS1OaepCipher.doFinal(Base64.decodeUnpadded(value));
						aesKey = new SecretKeySpec(aesKeyRaw, "AES");
					}
					else if ("aesiv".equals(key)) {
						/* Sets the AES initialization vector */
						aesIv = new IvParameterSpec(Base64.decodeUnpadded(value));
					}
					break;

				default:
					/* Ignore */
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

		/* Create decryption handler if an AES key and IV was specified */
		if ((aesKey != null) && (aesIv != null))
			m_audioDecryptHandler = new RaopRtpAudioDecryptionHandler(aesKey, aesIv);

		/* Create an ALAC decoder. The ALAC decoder is our stream information provider */
		final RaopRtpAudioAlacDecodeHandler handler = new RaopRtpAudioAlacDecodeHandler(formatOptions);
		m_audioDecodeHandler = handler;

		/* Create audio output queue with the format information provided by the ALAC decoder */
		AudioOutputQueue audioOutputQueue =
		m_audioOutputQueue = new AudioOutputQueue(handler, m_audioStream, m_channelMode);

		/* Create timing handle, using the AudioOutputQueue as time source */
		m_timingHandler = new RaopRtpTimingHandler(audioOutputQueue);

		/* Create retransmit request handler using the audio output queue as time source */
		m_resendRequestHandler = new RaopRtpRetransmitRequestHandler(handler, audioOutputQueue);

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
	private static Pattern s_pattern_transportOption = Pattern.compile("^([A-Za-z0-9_-]+)(=(.*))?$");

	/**
	 * Handles SETUP requests and creates the audio, control and timing RTP channels
	 */
	public synchronized void setupReceived(final ChannelHandlerContext ctx, final HttpRequest req)
		throws ProtocolException
	{
		/* Request must contain a Transport header */
		if (!req.containsHeader(HeaderTransport))
			throw new ProtocolException("No Transport header");

		/* Split Transport header into individual options and prepare reponse options list */
		final Deque<String> requestOptions = new java.util.LinkedList<String>(Arrays.asList(req.getHeader(HeaderTransport).split(";")));
		final List<String> responseOptions = new java.util.LinkedList<String>();

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
				s_logger.info("Launched RTP control service on " + channel.getLocalAddress());
				responseOptions.add("control_port=" + ((InetSocketAddress)channel.getLocalAddress()).getPort());
			}
			else if ("timing_port".equals(key)) {
				/* Port number of the client's timing socket. Response includes port number of *our* timing port */
				final int clientTimingPort = Integer.valueOf(value);
				final Channel channel = createRtpChannel(
					Utils.substitutePort(localAddress, RTP_TIMING_PORT),
					Utils.substitutePort(remoteAddress, clientTimingPort),
					RaopRtpChannelType.Timing
				);
				s_logger.info("Launched RTP timing service on " + channel.getLocalAddress());
				responseOptions.add("timing_port=" + ((InetSocketAddress)channel.getLocalAddress()).getPort());
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
		responseOptions.add("server_port=" + ((InetSocketAddress)channel.getLocalAddress()).getPort());

		/* Send response */
		final HttpResponse response = new DefaultHttpResponse(RtspVersions.RTSP_1_0,  RtspResponseStatuses.OK);
		response.addHeader(HeaderTransport, Utils.buildTransportOptions(responseOptions));
		response.addHeader(HeaderSession, "DEADBEEEF");
		ctxChannel.write(response);
	}

	/**
	 * Handles RECORD request. We did all the work during ANNOUNCE and SETUP, so there's nothing
	 * more to do.
	 *
	 * iTunes reports the initial RTP sequence and playback time here, which would actually be
	 * helpful. But iOS doesn't, so we ignore it all together.
	 */
	public synchronized void recordReceived(final ChannelHandlerContext ctx, final HttpRequest req)
		throws Exception
	{
		if (m_audioDecodeHandler == null)
			throw new ProtocolException("Audio stream not configured, cannot start recording");

		AudioOutputQueue audioOutputQueue = m_audioOutputQueue;
		RaopRtpTimingHandler timingHandler = m_timingHandler;
		if (audioOutputQueue != null)
			audioOutputQueue.start();
		if (timingHandler != null)
			timingHandler.start();

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
		AudioOutputQueue audioOutputQueue = m_audioOutputQueue;
		if (audioOutputQueue != null)
			audioOutputQueue.flush();

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

	/**
	 * Handle SET_PARAMETER request. Currently only {@code volume} is supported
	 */
	public synchronized void setParameterReceived(final ChannelHandlerContext ctx, final HttpRequest req)
		throws ProtocolException
	{
		/* Body in ASCII encoding with unix newlines */
		final String body = req.getContent().toString(s_utf8_charset).replace("\r", "");

		/* Handle parameters */
		for (final String line: body.split("\n")) {
			try {
				/* Split parameter into name and value */
				final Matcher m_parameter = s_pattern_parameter.matcher(line);
				if (!m_parameter.matches())
					throw new ProtocolException("Cannot parse line " + line);

				final String name = m_parameter.group(1);
				final String value = m_parameter.group(2);
				s_logger.info("RTSP parameter received: " + name + " = " + value);

				if ("volume".equals(name)) {
					/* Set output gain */
					AudioOutputQueue audioOutputQueue = m_audioOutputQueue;
					if (audioOutputQueue != null) {
						float volume = Float.parseFloat(value);
						audioOutputQueue.setGain(AudioVolume.fromAirTunes(volume));
					}
				}
			}
			catch (final Throwable e) {
				throw new ProtocolException("Unable to parse line " + line);
			}
		}

		final HttpResponse response = new DefaultHttpResponse(RtspVersions.RTSP_1_0,  RtspResponseStatuses.OK);
		ctx.getChannel().write(response);
	}

	/**
	 * Handle GET_PARAMETER request. Currently only {@code volume} is supported
	 */
	public synchronized void getParameterReceived(final ChannelHandlerContext ctx, final HttpRequest req)
		throws ProtocolException
	{
		final StringBuilder body = new StringBuilder();

		AudioOutputQueue audioOutputQueue = m_audioOutputQueue;
		if (audioOutputQueue != null) {
			/* Report output gain */
			body.append("volume: ");
			body.append(AudioVolume.toAirTunes(audioOutputQueue.getGain()));
			body.append("\r\n");
		}

		final HttpResponse response = new DefaultHttpResponse(RtspVersions.RTSP_1_0,  RtspResponseStatuses.OK);
		response.setContent(ChannelBuffers.wrappedBuffer(body.toString().getBytes(s_utf8_charset)));
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
		/* Create bootstrap helper for a data-gram socket using OIO,
		 * default is NIO which not work <= Android 5.1 ???
		 */
		final ConnectionlessBootstrap bootstrap = new ConnectionlessBootstrap(new OioDatagramChannelFactory(m_executorService));

		/* Set the buffer size predictor to 1500 bytes to ensure that
		 * received packets will fit into the buffer. Packets are
		 * truncated if they are larger than that!
		 */
		bootstrap.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(1500));

		/* Set the socket's receive buffer size. We set it to 1MB */
		bootstrap.setOption("receiveBufferSize", 1024*1024);

		/* Set pipeline factory for the RTP channel */
		bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
			@Override
			public ChannelPipeline getPipeline() throws Exception {
				final ChannelPipeline pipeline = Channels.pipeline();
				pipeline.addLast("executionHandler", m_executionHandler);
				pipeline.addLast("exceptionLogger", m_exceptionLoggingHandler);
				pipeline.addLast("decoder", m_decodeHandler);
				pipeline.addLast("encoder", m_encodeHandler);
				if (channelType.equals(RaopRtpChannelType.Audio)) {
					if (m_audioDecryptHandler != null)
						pipeline.addLast("decrypt", m_audioDecryptHandler);
					if (m_audioDecodeHandler != null)
						pipeline.addLast("audioDecode", m_audioDecodeHandler);
					pipeline.addLast("enqueue", m_enqueueHandler);
				} else {
					pipeline.addLast("timing", m_timingHandler);
					pipeline.addLast("resendRequester", m_resendRequestHandler);
				}
				return pipeline;
			}
		});

		/* Bind to local address */
		Channel channel = bootstrap.bind(local);

		/* Add to group of RTP channels beloging to this RTSP connection */
		m_rtpChannels.add(channel);

		/* Connect to remote address if one was provided */
		if (remote != null)
			channel.connect(remote);

		return channel;
	}
}
