package io.github.boundrail.context.spring.support;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * An {@link InetAddressResolverProvider}, registered for the test JVM, that records lookups of names under
 * {@code boundrail-dns-probe.invalid} and answers them with {@link UnknownHostException}, so no probe reaches the network. Every other
 * forward and reverse lookup is passed to the built-in resolver unchanged.
 */
public final class RecordingResolverProvider extends InetAddressResolverProvider {

    private static final String PROBE_DOMAIN = "boundrail-dns-probe.invalid";
    private static final Set<String> RECORDED = ConcurrentHashMap.newKeySet();

    /** {@return a fresh name under {@code boundrail-dns-probe.invalid}, never looked up before} */
    public static String probeName() {
        return UUID.randomUUID() + "." + PROBE_DOMAIN;
    }

    /** {@return whether a lookup of the given probe name has been recorded} */
    public static boolean wasLookedUp(String name) {
        return RECORDED.contains(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy) throws UnknownHostException {
                String name = host.toLowerCase(Locale.ROOT);
                if (name.equals(PROBE_DOMAIN) || name.endsWith("." + PROBE_DOMAIN)) {
                    RECORDED.add(name);
                    throw new UnknownHostException(host);
                }
                return builtin.lookupByName(host, lookupPolicy);
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return builtin.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "boundrail-recording-resolver";
    }
}
