package com.recranet.jenkins.plugins.upcloud;

import hudson.Extension;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import java.util.Collection;
import jenkins.model.Jenkins;

/**
 * Provisions agents for the builds that are waiting right now. The strategy of Jenkins itself acts on a load
 * average and lets builds wait for minutes before it provisions, on top of the time a server takes to start.
 */
@Extension(ordinal = 100)
public class UpCloudProvisioningStrategy extends NodeProvisioner.Strategy {

    @Override
    public NodeProvisioner.StrategyDecision apply(NodeProvisioner.StrategyState state) {
        int demand = state.getSnapshot().getQueueLength();
        CLOUD:
        for (Cloud cloud : Jenkins.get().clouds) {
            int shortage = demand - capacity(state);
            if (shortage <= 0) {
                break;
            }
            if (!(cloud instanceof UpCloudCloud upCloud) || !upCloud.isNoDelayProvisioning()) {
                continue;
            }
            Cloud.CloudState cloudState = new Cloud.CloudState(state.getLabel(), state.getAdditionalPlannedCapacity());
            if (!cloud.canProvision(cloudState)) {
                continue;
            }
            for (CloudProvisioningListener listener : CloudProvisioningListener.all()) {
                if (listener.canProvision(cloud, cloudState, shortage) != null) {
                    continue CLOUD;
                }
            }
            Collection<PlannedNode> planned = cloud.provision(cloudState, shortage);
            for (CloudProvisioningListener listener : CloudProvisioningListener.all()) {
                listener.onStarted(cloud, state.getLabel(), planned);
            }
            state.recordPendingLaunches(planned);
        }
        // Left to itself the standard strategy would provision once more when there are no executors at all.
        return capacity(state) >= demand
                ? NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED
                : NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
    }

    /** The executors that are free, or will be once agents that are on their way have connected. */
    private static int capacity(NodeProvisioner.StrategyState state) {
        LoadStatistics.LoadStatisticsSnapshot snapshot = state.getSnapshot();
        return snapshot.getAvailableExecutors()
                + snapshot.getConnectingExecutors()
                + state.getPlannedCapacitySnapshot()
                + state.getAdditionalPlannedCapacity();
    }

    /** Has the provisioner look at a build the moment it becomes buildable instead of on its next periodic run. */
    @Extension
    public static class BuildableListener extends QueueListener {

        @Override
        public void onEnterBuildable(Queue.BuildableItem item) {
            Jenkins jenkins = Jenkins.get();
            Label label = item.getAssignedLabel();
            Cloud.CloudState cloudState = new Cloud.CloudState(label, 0);
            for (UpCloudCloud cloud : jenkins.clouds.getAll(UpCloudCloud.class)) {
                if (cloud.isNoDelayProvisioning() && cloud.canProvision(cloudState)) {
                    (label == null ? jenkins.unlabeledNodeProvisioner : label.nodeProvisioner).suggestReviewNow();
                    return;
                }
            }
        }
    }
}
