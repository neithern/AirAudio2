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
    private final ArrayList<JmDNS> jmDNSInstances = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HashSet<InetAddress> selfAddresses = new HashSet<>();
    private ServiceListener externalListener;

    public boolean create(ServiceListener listener) {
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
        if (selfAddresses.contains(event.getInfo().getInetAddress()))
            return;

        handler.post(new Runnable() {
            @Override
            public void run() {
                ServiceListener listener = externalListener;
                if (listener != null)
                    listener.serviceAdded(event);
            }
        });
    }

    @Override
    public void serviceRemoved(final ServiceEvent event) {
        if (selfAddresses.contains(event.getInfo().getInetAddress()))
            return;

        handler.post(new Runnable() {
            @Override
            public void run() {
                ServiceListener listener = externalListener;
                if (listener != null)
                    listener.serviceRemoved(event);
            }
        });
    }

    @Override
    public void serviceResolved(final ServiceEvent event) {
        if (selfAddresses.contains(event.getInfo().getInetAddress()))
            return;

        handler.post(new Runnable() {
            @Override
            public void run() {
                ServiceListener listener = externalListener;
                if (listener != null)
                    listener.serviceResolved(event);
            }
        });
    }
}
