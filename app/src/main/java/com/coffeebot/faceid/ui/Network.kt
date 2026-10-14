package com.coffeebot.faceid.ui

import java.net.Inet4Address
import java.net.NetworkInterface

/** IPv4-адреса планшета в локальной сети — чтобы показать адрес веб-админки. */
fun localIpAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress ?: "" }
        .filter { it.isNotEmpty() }
}.getOrDefault(emptyList())
