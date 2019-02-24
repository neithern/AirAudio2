package com.github.neithern.airaudio;

import android.util.Log;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.ChannelGroupFuture;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.execution.ExecutionHandler;
import org.jboss.netty.handler.execution.OrderedMemoryAwareThreadPoolExecutor;
import org.phlo.AirReceiver.HardwareAddressMap;
import org.phlo.AirReceiver.RaopRtspPipelineFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

public class AirAudioServer {
    private static final String TAG = "AirAudioServer";

    private static final String AIR_TUNES_SERVICE_TYPE = "_raop._tcp.local.";

    private static final Map<String, String> AIRTUNES_SERVICE_PROPERTIES = map(
            "txtvers", "1",
            "tp", "UDP",
            "ch", "2",
            "ss", "16",
            "sr", "44100",
            "pw", "false",
            "sm", "false",
            "sv", "false",
            "ek", "1",
            "et", "0,1",
            "cn", "0,1",
            "vn", "3");

    private static class SingletonHolder {
        private final static AirAudioServer mInstance = new AirAudioServer();
    }

    public static AirAudioServer instance() {
        return SingletonHolder.mInstance;
    }

    private boolean running;
    private final ChannelGroup channelGroup = new DefaultChannelGroup();
    private final ArrayList<JmDNS> jmDNSInstances = new ArrayList<>();
    private final HardwareAddressMap hardwareAddresses = new HardwareAddressMap();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ExecutionHandler executionHandler = new ExecutionHandler(
            new OrderedMemoryAwareThreadPoolExecutor(4, 0, 0)
    );

    public boolean isRunning() {
        return running;
    }

    public boolean start(String displayName, int rtspPort, int audioStream) {
        Enumeration<NetworkInterface> eni = null;
        try {
            eni = NetworkInterface.getNetworkInterfaces();
        } catch (Exception e) {
            Log.e(TAG, "Enum net work failed", e);
            return false;
        }

        /* Create AirTunes RTSP server */
        RaopRtspPipelineFactory factory = new RaopRtspPipelineFactory(
                audioStream,
                executor, executionHandler, hardwareAddresses,
                new SimpleChannelUpstreamHandler() {
                    @Override
                    public void channelOpen (ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
                        channelGroup.add(e.getChannel());
                        super.channelOpen(ctx, e);
                    }
                });
        ServerBootstrap serverBootstrap = new ServerBootstrap(new NioServerSocketChannelFactory(executor, executor));
        serverBootstrap.setPipelineFactory(factory);
        serverBootstrap.setOption("reuseAddress", true);
        serverBootstrap.setOption("child.tcpNoDelay", true);
        serverBootstrap.setOption("child.keepAlive", true);

        try {
            Channel channel = serverBootstrap.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), rtspPort));
            rtspPort = ((InetSocketAddress) channel.getLocalAddress()).getPort();
            channelGroup.add(channel);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind RTSP server on port: " + rtspPort, e);
            return false;
        }

        running = false;
        while (eni.hasMoreElements()) {
            NetworkInterface ni = eni.nextElement();
            byte[] hwAddr;
            try {
                if (ni.isLoopback() || ni.isPointToPoint() || !ni.isUp())
                    continue;
                hwAddr = Arrays.copyOfRange(ni.getHardwareAddress(), 0, 6);
            } catch (Exception e) {
                continue;
            }

            Enumeration<InetAddress> eia = ni.getInetAddresses();
            while (eia.hasMoreElements()) {
                InetAddress addr = eia.nextElement();
                if (addr.isLoopbackAddress() || addr.isLinkLocalAddress())
                    continue;
                hardwareAddresses.put(addr, hwAddr);
                try {
                    JmDNS jmDNS = JmDNS.create(addr, displayName + "-jmdns");
                    jmDNSInstances.add(jmDNS);

                    /* Publish RAOP service */
                    ServiceInfo serviceInfo = ServiceInfo.create(
                            AIR_TUNES_SERVICE_TYPE,
                            toHexString(hwAddr) + "@" + displayName, rtspPort,
                            0 /* weight */, 0 /* priority */,
                            AIRTUNES_SERVICE_PROPERTIES
                    );
                    jmDNS.registerService(serviceInfo);
                    running = true;
                    Log.d(TAG, "Registered AirTunes server " + serviceInfo.getName() + " on " + addr + ':' + rtspPort);
                } catch (Exception e) {
                    Log.e(TAG, "Register AirTunes server failed on " + addr + addr + ':' + rtspPort, e);
                }
            }
        }
        return running;
    }

    public void stop() {
        /* Close channels */
        final ChannelGroupFuture allChannelsClosed = channelGroup.close();
        /* Wait for all channels to finish closing */
        allChannelsClosed.awaitUninterruptibly();
        running = false;
    }

    private static Map<String, String> map(final String... keyValues) {
        final Map<String, String> map = new HashMap<>(keyValues.length / 2);
        for(int i = 0; i < keyValues.length; i += 2)
            map.put(keyValues[i], keyValues[i+1]);
        return map;
    }

    private String toHexString(final byte[] bytes) {
        final StringBuilder s = new StringBuilder();
        for(final byte b: bytes) {
            final String h = Integer.toHexString(0x100 | b);
            s.append(h.substring(h.length() - 2).toUpperCase());
        }
        return s.toString();
    }
}
