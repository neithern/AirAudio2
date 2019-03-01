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

import java.net.InetSocketAddress;
import java.util.List;

public class Utils {

    public static String buildTransportOptions(List<String> options) {
        final StringBuilder builder = new StringBuilder();
        for (String opt: options) {
            if (builder.length() > 0)
                builder.append(";");
            builder.append(opt);
        }
        return builder.toString();
    }

    /**
     * Modifies the port component of an {@link InetSocketAddress} while
     * leaving the other parts unmodified.
     *
     * @param address socket address
     * @param port new port
     * @return socket address with port substitued
     */
    public static InetSocketAddress substitutePort(final InetSocketAddress address, final int port) {
        return new InetSocketAddress(address.getAddress(), port);
    }
}
