package io.github.boundrail.context.spring;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Trusts a request exactly when its {@code getRemoteAddr()} is inside one of the trusted CIDRs (IN-03, IN-08). Addresses are parsed without
 * DNS lookups; a remote address that is not an IP literal, or has an IPv6 zone ID, matches nothing.
 */
final class CidrInboundTrustVerifier implements InboundTrustVerifier {

    private static final String OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])";
    private static final Pattern IPV4 = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f.]*+:[0-9A-Fa-f:.]*+");
    /** The longest textual IP literal, {@code ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255}. */
    private static final int MAX_LITERAL_LENGTH = 45;

    private final List<Cidr> cidrs;

    CidrInboundTrustVerifier(List<String> entries) {
        this.cidrs = entries.stream().map(CidrInboundTrustVerifier::cidr).toList();
    }

    @Override
    public Trust verify(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        InetAddress address = literal(request.getRemoteAddr());
        if (address == null) {
            return Trust.UNTRUSTED;
        }
        byte[] bytes = address.getAddress();
        return cidrs.stream().anyMatch(cidr -> cidr.contains(bytes)) ? Trust.TRUSTED_INTERNAL : Trust.UNTRUSTED;
    }

    private static Cidr cidr(String entry) {
        int slash = entry.indexOf('/');
        InetAddress address = slash < 0 ? null : literal(entry.substring(0, slash));
        if (address == null) {
            throw new IllegalArgumentException("Invalid boundrail.context.inbound.trusted-cidrs entry: " + entry);
        }
        return new Cidr(address.getAddress(), Integer.parseInt(entry.substring(slash + 1)));
    }

    /**
     * Parses an IPv4 dotted quad or an IPv6 literal without a zone ID, or returns {@code null}. Only text that is textually such a literal
     * reaches {@code InetAddress.getByName}, which does no lookup for a literal. Longer text is rejected before any regex runs.
     */
    private static InetAddress literal(String text) {
        if (text == null || text.length() > MAX_LITERAL_LENGTH || !(IPV4.matcher(text).matches() || IPV6.matcher(text).matches())) {
            return null;
        }
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private record Cidr(byte[] network, int prefix) {

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            for (int i = 0; i < network.length; i++) {
                int bits = Math.clamp(prefix - 8 * i, 0, 8);
                int mask = (0xFF << (8 - bits)) & 0xFF;
                if (((address[i] ^ network[i]) & mask) != 0) {
                    return false;
                }
            }
            return true;
        }
    }
}
