package io.github.boundrail.context.spring;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Trusts a request exactly when its {@code getRemoteAddr()} is inside one of the trusted CIDRs (IN-03, IN-08). Addresses are parsed without
 * DNS lookups; a remote address that is not an IP literal, or has an IPv6 zone ID, matches nothing. One enclosing pair of square brackets is
 * removed from the remote address, never from an entry. An invalid entry throws {@code IllegalArgumentException} at construction.
 */
final class CidrInboundTrustVerifier implements InboundTrustVerifier {

    private static final String OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])";
    private static final Pattern IPV4 = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f.]*+:[0-9A-Fa-f:.]*+");
    private static final Pattern PREFIX = Pattern.compile("0|[1-9][0-9]{0,2}");
    /** The longest textual IP literal, {@code ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255}. */
    private static final int MAX_LITERAL_LENGTH = 45;

    private final List<Cidr> cidrs;

    CidrInboundTrustVerifier(List<String> entries) {
        this.cidrs = entries.stream().map(CidrInboundTrustVerifier::cidr).toList();
    }

    @Override
    public Trust verify(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        InetAddress address = literal(unbracketed(request.getRemoteAddr()));
        if (address == null) {
            return Trust.UNTRUSTED;
        }
        byte[] bytes = address.getAddress();
        return cidrs.stream().anyMatch(cidr -> cidr.contains(bytes)) ? Trust.TRUSTED_INTERNAL : Trust.UNTRUSTED;
    }

    private static Cidr cidr(String entry) {
        int slash = entry.indexOf('/');
        String text = slash < 0 ? entry : entry.substring(0, slash);
        InetAddress address = literal(text);
        // getByName turns ::ffff:a.b.c.d into an Inet4Address: an IPv4-mapped entry is not valid (IN-08).
        if (address == null || (address instanceof Inet4Address && !IPV4.matcher(text).matches())) {
            throw invalid(entry);
        }
        byte[] network = address.getAddress();
        int prefix = 8 * network.length;
        if (slash >= 0) {
            String length = entry.substring(slash + 1);
            if (!PREFIX.matcher(length).matches()) {
                throw invalid(entry);
            }
            int parsed = Integer.parseInt(length);
            if (parsed > prefix) {
                throw invalid(entry);
            }
            prefix = parsed;
        }
        Cidr cidr = new Cidr(network, prefix);
        if (!cidr.hasNoHostBits()) {
            throw invalid(entry);
        }
        return cidr;
    }

    private static IllegalArgumentException invalid(String entry) {
        return new IllegalArgumentException("Invalid boundrail.context.inbound.trusted-cidrs entry: " + entry);
    }

    /** Removes one enclosing pair of square brackets when the text has no other bracket; otherwise returns the text unchanged. */
    private static String unbracketed(String text) {
        if (text != null && text.length() >= 2 && text.charAt(0) == '[' && text.charAt(text.length() - 1) == ']') {
            String inner = text.substring(1, text.length() - 1);
            if (inner.indexOf('[') < 0 && inner.indexOf(']') < 0) {
                return inner;
            }
        }
        return text;
    }

    /**
     * Parses an IPv4 dotted quad or an IPv6 literal without a zone ID, or returns {@code null}. Only text that is textually such a literal
     * reaches {@code InetAddress.getByName}, which does no lookup for a literal. Longer text is rejected before any regex runs. In an IPv6
     * literal with an IPv4 part, that part must be a plain dotted quad, which {@code getByName} does not check.
     */
    private static InetAddress literal(String text) {
        if (text == null || text.length() > MAX_LITERAL_LENGTH) {
            return null;
        }
        boolean ipv6 = IPV6.matcher(text).matches()
                && (text.indexOf('.') < 0 || IPV4.matcher(text.substring(text.lastIndexOf(':') + 1)).matches());
        if (!(IPV4.matcher(text).matches() || ipv6)) {
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
                if (((address[i] ^ network[i]) & mask(i)) != 0) {
                    return false;
                }
            }
            return true;
        }

        boolean hasNoHostBits() {
            for (int i = 0; i < network.length; i++) {
                if ((network[i] & ~mask(i) & 0xFF) != 0) {
                    return false;
                }
            }
            return true;
        }

        private int mask(int index) {
            int bits = Math.clamp(prefix - 8 * index, 0, 8);
            return (0xFF << (8 - bits)) & 0xFF;
        }
    }
}
