package com.recranet.jenkins.plugins.upcloud;

import hudson.model.Computer;
import hudson.security.ACL;
import hudson.security.Permission;
import hudson.slaves.AbstractCloudComputer;
import org.springframework.security.core.Authentication;

public class UpCloudComputer extends AbstractCloudComputer<UpCloudAgent> {

    public UpCloudComputer(UpCloudAgent agent) {
        super(agent);
    }

    /**
     * Whether the agent is neither connected nor being launched, because its launch failed or its connection
     * was lost. Nothing reconnects such an agent: {@link UpCloudRetentionStrategy} removes it.
     */
    boolean isDisconnected() {
        return getChannel() == null && !isConnecting() && getOfflineCause() != null;
    }

    /** Agents are defined by the template they were created from and cannot be configured individually. */
    @Override
    public ACL getACL() {
        ACL acl = super.getACL();
        return new ACL() {
            @Override
            public boolean hasPermission2(Authentication authentication, Permission permission) {
                return permission != Computer.CONFIGURE && acl.hasPermission2(authentication, permission);
            }
        };
    }
}
