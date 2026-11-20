package com.recranet.jenkins.plugins.upcloud;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.recranet.jenkins.plugins.upcloud.client.Server;
import com.recranet.jenkins.plugins.upcloud.client.UpCloudClient;
import com.trilead.ssh2.Connection;
import hudson.AbortException;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.util.Timer;

/**
 * Creates the server of an agent, waits until it is ready and then starts the agent on it over SSH.
 */
public class UpCloudLauncher extends ComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(UpCloudLauncher.class.getName());

    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(10);
    private static final int SSH_CONNECT_TIMEOUT_MILLIS = 10_000;

    /**
     * Blocks until cloud-init, where the image has it, is done. Its exit status is the command's, so that a
     * failed init script is noticed.
     */
    private static final String AWAIT_INIT_COMMAND =
            "if command -v cloud-init >/dev/null 2>&1; then cloud-init status --wait; fi";

    private transient SSHLauncher delegate;

    @Override
    public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
        if (!(computer.getNode() instanceof UpCloudAgent agent)) {
            throw new IOException(computer.getName() + " is not an UpCloud agent");
        }
        try {
            launch(agent, computer, listener);
        } catch (IOException e) {
            // Jenkins reports an AbortException as its message, and any other exception as a probable bug in
            // Jenkins with a stack trace. A launch fails for reasons such as an error from UpCloud or a server
            // that cannot be reached, which the message tells.
            LOGGER.log(Level.FINE, "Failed to launch " + agent.getNodeName(), e);
            throw new AbortException(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void launch(UpCloudAgent agent, SlaveComputer computer, TaskListener listener)
            throws IOException, InterruptedException {
        UpCloudAgentTemplate template = agent.getTemplate();
        UpCloudClient client = agent.getCloud().client();
        PrintStream log = listener.getLogger();
        Duration timeout = Duration.ofSeconds(template.getStartupTimeoutSeconds());

        // The agent already has a server when Jenkins reconnects to it, e.g. after a restart of the controller.
        String uuid = agent.getServerUuid();
        if (uuid == null) {
            log.println(
                    "Creating an UpCloud server with plan " + template.getPlan() + " in zone " + template.getZone());
            Server created = client.createServer(template.serverSpec(agent.getNodeName(), client));
            agent.setServer(created);
            uuid = created.uuid();
        }
        log.println("Waiting for server " + uuid + " to start");
        Server server = client.waitForServerState(
                uuid, Server.STATE_STARTED, Instant.now().plus(timeout));

        String host = template.address(server);
        Instant deadline = Instant.now().plus(timeout);
        awaitInitialization(host, template, deadline, listener);

        // An image without cloud-init gives no way to wait for its init script, which may still be installing
        // Java: starting the agent is retried until it succeeds.
        while (true) {
            delegate = template.sshLauncher(host);
            delegate.launch(computer, listener);
            if (computer.getChannel() != null || Instant.now().isAfter(deadline)) {
                return;
            }
            log.println("The agent did not start, trying again");
            Thread.sleep(RETRY_INTERVAL.toMillis());
        }
    }

    /**
     * Waits until the server accepts the SSH credentials and has finished initializing. The host key is not
     * verified here: the server is new and UpCloud offers no way to learn its key beforehand.
     */
    private static void awaitInitialization(
            String host, UpCloudAgentTemplate template, Instant deadline, TaskListener listener)
            throws IOException, InterruptedException {
        PrintStream log = listener.getLogger();
        StandardUsernameCredentials credentials = template.sshCredentials();
        log.println("Waiting for SSH on " + host + ":" + template.getSshPort());
        while (true) {
            Connection connection = new Connection(host, template.getSshPort());
            // A server that accepts the connection and then goes quiet would block a read forever.
            ScheduledFuture<?> watchdog = Timer.get()
                    .schedule(
                            connection::close,
                            Math.max(
                                    0, Duration.between(Instant.now(), deadline).toMillis()),
                            TimeUnit.MILLISECONDS);
            try {
                connection.connect(null, SSH_CONNECT_TIMEOUT_MILLIS, SSH_CONNECT_TIMEOUT_MILLIS);
                if (!SSHAuthenticator.newInstance(connection, credentials).authenticate(listener)) {
                    throw new IOException("SSH authentication as " + credentials.getUsername() + " failed");
                }
                log.println("Waiting for the server to finish initializing");
                int status = connection.exec(AWAIT_INIT_COMMAND, log);
                if (status != 0) {
                    log.println("cloud-init finished with status " + status
                            + ", see /var/log/cloud-init-output.log on the server");
                }
                return;
            } catch (IOException e) {
                if (Instant.now().isAfter(deadline)) {
                    throw new IOException("Timed out waiting for SSH on " + host + ": " + e.getMessage(), e);
                }
                log.println("SSH is not ready yet: " + e.getMessage());
            } finally {
                watchdog.cancel(false);
                connection.close();
            }
            Thread.sleep(RETRY_INTERVAL.toMillis());
        }
    }

    @Override
    public void beforeDisconnect(SlaveComputer computer, TaskListener listener) {
        if (delegate != null) {
            delegate.beforeDisconnect(computer, listener);
        }
    }

    @Override
    public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
        if (delegate != null) {
            delegate.afterDisconnect(computer, listener);
        }
    }
}
