package com.recranet.jenkins.plugins.upcloud.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.Map;

/**
 * What to create a server from.
 *
 * @param name used as both the server's title and its hostname
 * @param storage UUID of the storage the boot disk is cloned from
 * @param storageTier null to let UpCloud pick its default tier
 * @param userData script body or URL run on first boot, null for none
 * @param loginUser user the SSH key is installed for; set together with {@code sshPublicKey} or not at all
 * @param privateNetwork UUID of an SDN private network to attach, null for none
 */
public record ServerSpec(
        String name,
        String zone,
        String plan,
        String storage,
        int storageSizeGb,
        @CheckForNull String storageTier,
        @CheckForNull String userData,
        @CheckForNull String loginUser,
        @CheckForNull String sshPublicKey,
        boolean publicIpv4,
        boolean publicIpv6,
        boolean utilityNetwork,
        @CheckForNull String privateNetwork,
        Map<String, String> labels) {}
