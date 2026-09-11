package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.utils.Util
import java.net.URI

/** Converts only generated objects, before their existing custom JSON overlays. */
internal fun migrateBuiltInConfig(options: MyOptions) {
    val earlyRules = mutableListOf<Rule>()
    fun inboundActions(tag: String, sniff: Boolean?, override: Boolean?, strategy: String?) {
        if (sniff == true) earlyRules += Rule_DefaultOptions().apply {
            inbound = listOf(tag)
            action = "sniff"
            if (override == true) _neko_sniff_override = true
        }
        if (!strategy.isNullOrEmpty()) earlyRules += Rule_DefaultOptions().apply {
            inbound = listOf(tag)
            action = "resolve"
            this.strategy = strategy
        }
    }
    options.inbounds.forEach { inbound ->
        when (inbound) {
            is Inbound_TunOptions -> {
                inboundActions(inbound.tag, inbound.sniff, inbound.sniff_override_destination, inbound.domain_strategy)
                inbound.sniff = null
                inbound.sniff_override_destination = null
                inbound.domain_strategy = null
                inbound.address = inbound.inet4_address.orEmpty() + inbound.inet6_address.orEmpty()
                inbound.inet4_address = null
                inbound.inet6_address = null
                if (inbound.endpoint_independent_nat == true) {
                    inbound.udp_mapping = "endpoint_independent"
                    inbound.udp_filtering = "endpoint_independent"
                }
                inbound.endpoint_independent_nat = null
            }
            is Inbound_MixedOptions -> {
                inboundActions(inbound.tag, inbound.sniff, inbound.sniff_override_destination, inbound.domain_strategy)
                inbound.sniff = null
                inbound.sniff_override_destination = null
                inbound.domain_strategy = null
            }
        }
    }
    options.route.rules = earlyRules + options.route.rules

    val strategies = options.dns.servers.associate { it.tag to it.strategy }
    val rcodes = mutableMapOf<String, String>()
    options.dns.servers = options.dns.servers.filter { server ->
        val address = server.address
        if (address.startsWith("rcode://")) {
            rcodes[server.tag] = when (address.removePrefix("rcode://")) {
                "success" -> "NOERROR"
                "format_error" -> "FORMERR"
                "server_failure" -> "SERVFAIL"
                "name_error" -> "NXDOMAIN"
                "not_implemented" -> "NOTIMP"
                "refused" -> "REFUSED"
                else -> error("Unknown DNS rcode: $address")
            }
            false
        } else {
            migrateDnsServer(server, options.dns.fakeip)
            true
        }
    }
    options.dns.fakeip = null
    val rules = options.dns.rules.filterNot {
        it is DNSRule_DefaultOptions && it.outbound == listOf("any") && it.server == "dns-direct"
    }.toMutableList()
    rules.forEach { rule ->
        if (rule is DNSRule_DefaultOptions) {
            val rcode = rcodes[rule.server]
            if (rcode != null) {
                rule.action = "predefined"
                rule.rcode = rcode
                rule.server = null
                rule.disable_cache = null
            } else {
                rule.strategy = strategies[rule.server]?.takeIf { it.isNotEmpty() }
            }
        }
    }
    // Legacy transport strategy also applied when the final transport was used.
    strategies[options.dns.final_]?.takeIf { it.isNotEmpty() }?.let { strategy ->
        rules += DNSRule_DefaultOptions().apply {
            server = options.dns.final_
            this.strategy = strategy
        }
    }
    options.dns.rules = rules
    val directResolver = mutableMapOf<String, Any>("server" to "dns-direct")
    strategies["dns-direct"]?.takeIf { it.isNotEmpty() }?.let { directResolver["strategy"] = it }
    options.route.default_domain_resolver = directResolver

    val outbounds = mutableListOf<SingBoxOption>()
    val endpoints = mutableListOf<SingBoxOption>()
    options.outbounds.forEachIndexed { index, outbound ->
        val generated = outbound._hack_config_map
        if (generated.containsKey("domain_strategy")) {
            val strategy = generated.remove("domain_strategy") as String
            generated["domain_resolver"] = directResolver.toMutableMap().apply {
                if (strategy.isNotEmpty()) put("strategy", strategy)
            }
        } else if (outbound is Outbound && outbound.type == "direct") {
            // The removed outbound:any DNS rule covered direct/bypass too.
            generated["domain_resolver"] = directResolver.toMutableMap()
        }
        if (outbound is Outbound_WireGuardOptions) {
            val endpoint = Endpoint_WireGuardOptions().apply {
                type = "wireguard"
                tag = outbound.tag
                address = outbound.local_address
                private_key = outbound.private_key
                mtu = outbound.mtu
                peers = listOf(mutableMapOf<String, Any>(
                    "address" to outbound.server,
                    "port" to outbound.server_port,
                    "public_key" to outbound.peer_public_key,
                    "allowed_ips" to listOf("0.0.0.0/0", "::/0")
                ).apply {
                    outbound.pre_shared_key?.takeIf { it.isNotEmpty() }?.let { put("pre_shared_key", it) }
                    outbound.reserved?.takeIf { it.isNotEmpty() }?.let {
                        put("reserved", Util.b64Decode(it).map { byte -> byte.toInt() and 255 })
                    }
                })
                _hack_config_map = generated
                _hack_custom_config = outbound._hack_custom_config
            }
            endpoints += endpoint
            if (index == 0 && options.route.final_.isNullOrEmpty()) {
                options.route.final_ = generated["tag"] as? String ?: outbound.tag
            }
        } else outbounds += outbound
    }
    options.outbounds = outbounds
    if (endpoints.isNotEmpty()) options.endpoints = endpoints
}

private fun migrateDnsServer(server: DNSServerOptions, fakeIP: DNSFakeIPOptions?) {
    val address = server.address
    server.address = null
    server.strategy = null
    server.address_resolver?.takeIf { it.isNotEmpty() }?.let { resolver ->
        server.domain_resolver = mutableMapOf<String, Any>("server" to resolver).apply {
            server.address_strategy?.takeIf { it.isNotEmpty() }?.let { put("strategy", it) }
        }
    }
    server.address_resolver = null
    server.address_strategy = null
    // The legacy remote DNS default dialer used the App's default outbound.
    if (server.tag == "dns-remote" && server.detour.isNullOrEmpty()) server.detour = "proxy"
    if (address == "local" || address.startsWith("local://")) {
        server.type = "local"
        return
    }
    if (address == "fakeip") {
        server.type = "fakeip"
        server.detour = null
        server.domain_resolver = null
        server.inet4_range = fakeIP?.inet4_range
        server.inet6_range = fakeIP?.inet6_range
        return
    }
    // Legacy UDP accepts a bare IPv6 literal as well as host:port.
    val authority = if (!address.startsWith("[") && address.count { it == ':' } > 1) "[$address]" else address
    val uri = URI(if (address.contains("://")) address else "udp://$authority")
    server.type = uri.scheme
    require(server.type in setOf("udp", "tcp", "tls", "https", "quic", "h3")) { "Unsupported DNS transport: $address" }
    server.server = requireNotNull(uri.host) { "Invalid DNS address: $address" }.removePrefix("[").removeSuffix("]")
    if (uri.port >= 0) server.server_port = uri.port
    if (server.type in setOf("tls", "https", "quic", "h3")) server.tls = mapOf("enabled" to true)
    if (server.type in setOf("https", "h3") && !uri.path.isNullOrEmpty()) server.path = uri.path
}
