package com.google.ai.edge.gallery.server

import java.net.Inet4Address
import java.net.NetworkInterface

/** The device's address on the local network, shown so other machines can reach the server. */
object NetworkAddress {
  /** Private IPv4 of the first active, non-loopback interface (Wi-Fi, Ethernet, hotspot), or null. */
  fun lanIp(): String? =
    try {
      NetworkInterface.getNetworkInterfaces()
        ?.toList()
        .orEmpty()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }
        ?.hostAddress
    } catch (e: Exception) {
      null
    }
}
