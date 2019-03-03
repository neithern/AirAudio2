package com.github.neithern.airaudio;

import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceListener;

public class AirPlayDiscovery implements ServiceListener {
    public static final int PROXY_PORT = AirAudioServer.PROXY_PORT;

    public interface Listener {
        void onServiceAdded(String name, String address, boolean self);
        void onServiceRemoved(String name, String address);
    }

    private final ArrayList<JmDNS> jmDNSInstances = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HashSet<InetAddress> selfAddresses = new HashSet<>();
    private Listener externalListener;

    public boolean create(Listener listener) {
        externalListener = listener;
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                startInternal();
            }
        });
        return true;
    }

    public void close() {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                closeInternal();
            }
        });
    }

    private void startInternal() {
        Enumeration<NetworkInterface> eni = null;
        try {
            eni = NetworkInterface.getNetworkInterfaces();
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        while (eni.hasMoreElements()) {
            NetworkInterface ni = eni.nextElement();
            try {
                if (ni.isLoopback() || ni.isPointToPoint() || !ni.isUp())
                    continue;
            } catch (Exception ignored) {
                continue;
            }

            Enumeration<InetAddress> eia = ni.getInetAddresses();
            while (eia.hasMoreElements()) {
                InetAddress addr = eia.nextElement();
                if (addr.isLoopbackAddress() || addr.isLinkLocalAddress())
                    continue;

                selfAddresses.add(addr);
                try {
                    JmDNS jmDNS = JmDNS.create(addr);
                    jmDNSInstances.add(jmDNS);
                    jmDNS.addServiceListener(AirAudioServer.AIRPLAY_SERVICE_TYPE, this);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void closeInternal() {
        for (JmDNS jmDNS : jmDNSInstances) {
            if (externalListener != null)
                jmDNS.removeServiceListener(AirAudioServer.AIRPLAY_SERVICE_TYPE, this);
            AirAudioServer.closeSilently(jmDNS);
        }
        jmDNSInstances.clear();
        externalListener = null;
    }

    @Override
    public void serviceAdded(final ServiceEvent event) {
        final InetAddress[] addresses = event.getInfo().getInetAddresses();
        if (addresses == null || addresses.length == 0)
            return;
        final int port = event.getInfo().getPort();
        if (port == PROXY_PORT)
            return;

        handler.post(new Runnable() {
            @Override
            public void run() {
                Listener listener = externalListener;
                if (listener != null) {
                    for (InetAddress address : addresses) {
                        final boolean self = selfAddresses.contains(address);
                        listener.onServiceAdded(getDisplayName(event.getName()),
                                getAddressWithPort(address, port), self);
                    }
                }
            }
        });
    }

    @Override
    public void serviceRemoved(final ServiceEvent event) {
        final InetAddress[] addresses = event.getInfo().getInetAddresses();
        if (addresses == null || addresses.length == 0)
            return;

        handler.post(new Runnable() {
            @Override
            public void run() {
                Listener listener = externalListener;
                if (listener != null) {
                    final int port = event.getInfo().getPort();
                    for (InetAddress address : addresses)
                        listener.onServiceRemoved(getDisplayName(event.getName()),
                                getAddressWithPort(address, port));
                }
            }
        });
    }

    @Override
    public void serviceResolved(final ServiceEvent event) {
        serviceAdded(event);
    }

    private static String getAddressWithPort(InetAddress address, int port) {
        return address.getHostAddress() + ':' + port;
    }

    private static String getDisplayName(String name) {
        return name.substring(name.indexOf('@') + 1);
    }
}
