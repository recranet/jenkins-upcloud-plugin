package com.recranet.jenkins.plugins.upcloud.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;

/**
 * A storage that servers can be cloned from.
 *
 * @param access {@code public} for templates provided by UpCloud, {@code private} for the account's own
 * @param zone zone the storage lives in, null for public templates, which are available in every zone
 * @param tier storage tier the storage is kept on, null when UpCloud does not tell
 */
public record Storage(
        String uuid,
        String title,
        String access,
        @CheckForNull String zone,
        int sizeGb,
        @CheckForNull String tier) {

    public boolean isPublic() {
        return "public".equals(access);
    }
}
