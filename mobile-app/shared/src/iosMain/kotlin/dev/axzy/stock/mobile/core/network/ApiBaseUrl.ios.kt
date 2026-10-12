package dev.axzy.stock.mobile.core.network

/** The iOS simulator shares the host's network, so the host is the loopback address. */
actual val defaultApiBaseUrl: String = "http://127.0.0.1:8081"
