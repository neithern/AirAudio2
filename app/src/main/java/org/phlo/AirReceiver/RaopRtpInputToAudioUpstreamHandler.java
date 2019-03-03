package org.phlo.AirReceiver;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.UpstreamMessageEvent;

/**
 * Routes incoming packets from the control and timing channel to
 * the audio channel
 */
public class RaopRtpInputToAudioUpstreamHandler extends SimpleChannelUpstreamHandler {
    private Channel m_rtpAudioChannel;

    public void setRtpAudioChannel(Channel channel) {
        m_rtpAudioChannel = channel;
    }

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent evt) throws Exception {
        /* Get audio channel from the enclosing ProxyServerHandler */
        final Channel audioChannel = m_rtpAudioChannel;
        if (audioChannel != null && audioChannel.isOpen() && audioChannel.isReadable()) {
            audioChannel.getPipeline().sendUpstream(
                    new UpstreamMessageEvent(audioChannel, evt.getMessage(), evt.getRemoteAddress()));
        }
    }
}
