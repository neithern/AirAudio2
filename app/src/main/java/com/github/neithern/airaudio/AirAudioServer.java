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

package com.github.neithern.airaudio;

import android.util.Log;

import com.github.neithern.airproxy.ProxyRtspPipelineFactory;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.ChannelGroupFuture;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.oio.OioServerSocketChannelFactory;
import org.jboss.netty.handler.execution.ExecutionHandler;
import org.jboss.netty.handler.execution.OrderedMemoryAwareThreadPoolExecutor;
import org.phlo.AirReceiver.AudioChannel;
import org.phlo.AirReceiver.HardwareAddressMap;
import org.phlo.AirReceiver.RaopRtspPipelineFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

public class AirAudioServer {
    private static final String TAG = "AirAudioServer";

    private static final int PLAYER_PORT = 46343;
    public static final int PROXY_PORT = 46344;

    public static final String AIRPLAY_SERVICE_TYPE = "_raop._tcp.local.";

    private static final Map<String, String> AIRPLAY_SERVICE_PROPERTIES = map(
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

    private final ChannelGroup channelGroup = new DefaultChannelGroup();
    private final ArrayList<JmDNS> jmDNSInstances = new ArrayList<>();
    private final HardwareAddressMap hardwareAddresses = new HardwareAddressMap();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ExecutionHandler executionHandler = new ExecutionHandler(
            new OrderedMemoryAwareThreadPoolExecutor(8, 0, 0));

    private final ChannelHandler closeHandler = new SimpleChannelUpstreamHandler() {
        @Override
        public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
            channelGroup.add(e.getChannel());
            super.channelOpen(ctx, e);
        }
    };

    private boolean running;

    public boolean isRunning() {
        return running;
    }

    public boolean start(String playerName, int audioStream, AudioChannel channelMode, String groupName, Set<InetSocketAddress> groupAddresses) {
        Enumeration<NetworkInterface> eni = null;
        try {
            eni = NetworkInterface.getNetworkInterfaces();
        } catch (Exception e) {
            Log.e(TAG, "Enum network failed", e);
            return false;
        }

        int playerPort = 0;
        int proxyPort = 0;

        if (playerName != null && !playerName.isEmpty()) {
            Channel channel = createPlayerServer(audioStream, channelMode);
            if (channel == null)
                return false;
            playerPort = ((InetSocketAddress) channel.getLocalAddress()).getPort();
        }

        CopyOnWriteArraySet<InetSocketAddress> serverSet = new CopyOnWriteArraySet<>();
        if (groupName != null && !groupName.isEmpty() && groupAddresses != null && !groupAddresses.isEmpty()) {
            serverSet.addAll(groupAddresses);
            Channel channel = createProxyServer(serverSet);
            if (channel == null) {
                closeChannels();
                return false;
            }
            proxyPort = ((InetSocketAddress) channel.getLocalAddress()).getPort();
        }

        running = false;
        while (eni.hasMoreElements()) {
            NetworkInterface ni = eni.nextElement();
            byte[] hwAddr;
            try {
                if (ni.isLoopback() || ni.isPointToPoint() || !ni.isUp())
                    continue;
                hwAddr = Arrays.copyOfRange(ni.getHardwareAddress(), 0, 6);
            } catch (Exception ignored) {
                continue;
            }

            Enumeration<InetAddress> eia = ni.getInetAddresses();
            while (eia.hasMoreElements()) {
                InetAddress addr = eia.nextElement();
                if (addr.isLoopbackAddress() || addr.isLinkLocalAddress())
                    continue;

                hardwareAddresses.put(addr, hwAddr);
                // remove address if duplicated
                serverSet.remove(new InetSocketAddress(addr, proxyPort));

                try {
                    JmDNS jmDNS = JmDNS.create(addr, playerName + "-jmdns");
                    jmDNSInstances.add(jmDNS);

                    if (playerPort != 0) {
                        ServiceInfo serviceInfo = ServiceInfo.create(AIRPLAY_SERVICE_TYPE,
                                toHexString(hwAddr) + "@" + playerName, playerPort,
                                0, 0,AIRPLAY_SERVICE_PROPERTIES);
                        jmDNS.registerService(serviceInfo);
                        Log.d(TAG, "Register player server " + serviceInfo.getName() + " on " + addr + ':' + playerPort);
                    }
                    if (proxyPort != 0) {
                        ServiceInfo serviceInfo = ServiceInfo.create(AIRPLAY_SERVICE_TYPE,
                                toHexString(hwAddr) + "@" + groupName, proxyPort,
                                0, 0, AIRPLAY_SERVICE_PROPERTIES);
                        jmDNS.registerService(serviceInfo);
                        Log.d(TAG, "Register group server " + serviceInfo.getName() + " on " + addr + ':' + proxyPort);
                    }
                    running = true;
                } catch (Exception e) {
                    Log.e(TAG, "Register server failed on " + addr + addr + ':' + playerPort, e);
                }
            }
        }
        return running;
    }

    public void stop() {
        /* Unregister services */
        for (JmDNS jmDNS : jmDNSInstances)
            closeSilently(jmDNS);
        jmDNSInstances.clear();

        closeChannels();
        running = false;
    }

    private void closeChannels() {
        final ChannelGroupFuture future = channelGroup.close();
        future.awaitUninterruptibly();
        channelGroup.clear();
    }

    private Channel createPlayerServer(int audioStream, AudioChannel channelMode) {
        RaopRtspPipelineFactory factory = new RaopRtspPipelineFactory(audioStream, channelMode,
                executor, executionHandler, hardwareAddresses, closeHandler);
        return crateServer(factory, PLAYER_PORT);
    }

    private Channel createProxyServer(CopyOnWriteArraySet<InetSocketAddress> serverSet) {
        ProxyRtspPipelineFactory factory = new ProxyRtspPipelineFactory(serverSet,
                executor, executionHandler, hardwareAddresses, closeHandler);
        return crateServer(factory, PROXY_PORT);
    }

    private Channel crateServer(ChannelPipelineFactory factory, int port) {
        ServerBootstrap bootstrap = new ServerBootstrap(new OioServerSocketChannelFactory(executor, executor));
        bootstrap.setPipelineFactory(factory);
        bootstrap.setOption("reuseAddress", true);
        bootstrap.setOption("child.keepAlive", true);
        try {
            Channel channel = bootstrap.bind(new InetSocketAddress("0.0.0.0", port));
            channelGroup.add(channel);
            return channel;
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind RTSP server on port: " + port, e);
            return null;
        }
    }

    private static Map<String, String> map(final String... keyValues) {
        final HashMap<String, String> map = new HashMap<>(keyValues.length / 2);
        for (int i = 0; i < keyValues.length; i += 2)
            map.put(keyValues[i], keyValues[i + 1]);
        return map;
    }

    private static String toHexString(final byte[] bytes) {
        final StringBuilder s = new StringBuilder();
        for (byte b : bytes) {
            String h = Integer.toHexString(0x100 | b);
            s.append(h.substring(h.length() - 2).toUpperCase());
        }
        return s.toString();
    }

    public static void closeSilently(Closeable c) {
        try {
            if (c != null)
                c.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Parse address like: 127.0.0.1:80
     */
    public static InetSocketAddress parseAddress(String addressAndPort) {
        try {
            int pos = addressAndPort.lastIndexOf(':');
            if (pos == -1)
                return null;
            return new InetSocketAddress(addressAndPort.substring(0, pos),
                    Integer.valueOf(addressAndPort.substring(pos + 1)));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
