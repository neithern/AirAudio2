package org.phlo.AirReceiver;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelDownstreamHandler;

/**
 * Routes outgoing packets on audio channel to the control or timing
 * channel if appropriate
 */
public class RaopRtpAudioToOutputDownstreamHandler extends SimpleChannelDownstreamHandler {
    private Channel m_rtpControlChannel;
    private Channel m_rtpTimingChannel;

    public void setRtpChannels(Channel controlChannel, Channel timingChannel) {
        m_rtpControlChannel = controlChannel;
        m_rtpTimingChannel = timingChannel;
    }

    @Override
    public void writeRequested(final ChannelHandlerContext ctx, final MessageEvent evt) throws Exception {
        final RaopRtpPacket packet = (RaopRtpPacket) evt.getMessage();
        /* Get control and timing channel from the enclosing ProxyServerHandler */
        if (packet instanceof RaopRtpPacket.RetransmitRequest) {
            final Channel controlChannel = m_rtpControlChannel;
            if (controlChannel != null && controlChannel.isOpen() && controlChannel.isWritable())
                controlChannel.write(evt.getMessage());
        } else if (packet instanceof RaopRtpPacket.TimingRequest) {
            final Channel timingChannel = m_rtpTimingChannel;
            if (timingChannel != null && timingChannel.isOpen() && timingChannel.isWritable())
                timingChannel.write(evt.getMessage());
        } else {
            super.writeRequested(ctx, evt);
        }
    }
}
