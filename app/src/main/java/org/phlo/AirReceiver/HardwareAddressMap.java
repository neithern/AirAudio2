package org.phlo.AirReceiver;

import java.net.InetAddress;

public interface HardwareAddressMap {
    byte[] getHardwareAddress(InetAddress address);
}
