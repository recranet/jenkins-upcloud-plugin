package com.recranet.jenkins.plugins.upcloud.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.time.Instant;
import java.util.List;

/**
 * @param addresses empty in list responses, which do not include addresses
 */
public record Server(String uuid, String title, String state, Instant created, List<Address> addresses) {

    public static final String STATE_STARTED = "started";
    public static final String STATE_STOPPED = "stopped";
    public static final String STATE_ERROR = "error";

    /**
     * @param access {@code public}, {@code utility} or {@code private}
     * @param family {@code IPv4} or {@code IPv6}
     */
    public record Address(String access, String family, String address) {}

    /** The server's first address of a family on a network of the given access type. */
    @CheckForNull
    public String address(String access, String family) {
        return addresses.stream()
                .filter(a -> a.access().equals(access) && a.family().equals(family))
                .map(Address::address)
                .findFirst()
                .orElse(null);
    }
}
