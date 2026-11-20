package com.recranet.jenkins.plugins.upcloud.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;

/**
 * A server plan.
 *
 * @param storageSizeGb size of the storage included in the plan, 0 when the plan includes none
 * @param storageTier tier of the included storage, null when the plan includes none
 */
public record Plan(
        String name,
        int cores,
        int memoryMb,
        int storageSizeGb,
        @CheckForNull String storageTier) {}
