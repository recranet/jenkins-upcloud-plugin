# UpCloud Cloud Plugin for Jenkins

Runs Jenkins build agents on [UpCloud](https://upcloud.com/) Cloud Servers. A server is created when builds are
waiting for an agent, and deleted again when the agent has been idle for a while.

- Scales with the build queue, within limits you set per cloud and per template
- Servers are created from any public UpCloud template or from your own custom images
- An initialization script prepares the server on first boot, for example to install Java
- Agents are started over SSH; the key is installed on the server when it is created
- Works with public, utility and private networking
- Configurable through the UI and through [Configuration as Code](https://plugins.jenkins.io/configuration-as-code/)

## Requirements

- Jenkins 2.528.3 or newer
- An UpCloud account with API access
- Images that run Linux and are reachable over SSH. Java has to be on the server before the agent can start, either
  as part of a custom image or installed by the initialization script.

## Installation

Until the plugin is available from the Jenkins update center, build it with `mvn package` and upload
`target/upcloud-cloud.hpi` under **Manage Jenkins » Plugins » Advanced settings » Deploy Plugin**.

## Getting started

### 1. Credentials

Add two credentials under **Manage Jenkins » Credentials**:

- **UpCloud API**: a *Secret text* credential holding an [API token](https://developers.upcloud.com/1.3/24-api-tokens/),
  or a *Username with password* credential of an account with API access. A token or sub-account dedicated to Jenkins
  is recommended. It has to be allowed to create, stop and delete servers and their storages.
- **SSH**: an *SSH Username with private key* credential. Jenkins derives the public key from it and has UpCloud
  install that key for the given user when a server is created, so there is nothing to upload to UpCloud. A
  *Username with password* credential works too, but only with a custom image that already accepts that login and
  does not use cloud-init: UpCloud requires a key for cloud-init images.

### 2. Cloud

Go to **Manage Jenkins » Clouds » New cloud**, pick **UpCloud**, select the API credentials and use **Test
connection** to check them.

| Setting | Description |
| --- | --- |
| Instance cap | Maximum number of agents of this cloud at the same time. 0 means no limit. |
| Provision agents without delay | Create a server as soon as a build is waiting, instead of letting Jenkins decide based on the load average, which usually takes minutes. On by default. |
| Delete orphaned servers | Periodically delete servers of this controller that no agent refers to any more, see [Orphaned servers](#orphaned-servers). On by default. |

### 3. Agent templates

A template describes the servers created for a set of labels. A cloud can have any number of them.

| Setting | Description |
| --- | --- |
| Name | Agents and servers are named after the template plus a random suffix, e.g. `linux-3f9c2a1b`. |
| Labels, Usage, Number of executors | As for any Jenkins agent. |
| Zone | The UpCloud data center. |
| Plan | The server plan: CPU cores, memory and included storage. |
| Image | The storage template the disk is cloned from: a public template, or a custom image of your account. Custom images are listed with their zone and can only be used there. |
| Initialization script | A shell script, or the URL of one, that runs as root on first boot. |
| Storage size, Storage tier | Default to what the plan includes. A custom image is always cloned into the tier it is stored on, as UpCloud does not allow another one. |
| Public IPv4, Public IPv6, Utility network, SDN private network, Connect to the agent over | Which networks the server is attached to and which one Jenkins uses. See [Networking](#networking). |
| SSH credentials, SSH port | How Jenkins logs in to the server. |
| Remote root directory | The directory the agent works in. |
| Remove an idle agent | When an agent without builds is removed and its server deleted: after a number of idle minutes, or just before the billing hour of its server ends. See [Removing idle agents](#removing-idle-agents). |
| Instance cap | Maximum number of agents of this template at the same time. 0 means no limit. |
| Startup timeout | How long a server may take to start, and then to become reachable, finish initializing and start the agent. |
| Java path, JVM options | For the agent process. |
| Node properties | Properties every agent of the template gets, such as environment variables. See [Node properties](#node-properties). |

Every setting has inline help in the UI.

## Images and initialization scripts

With a **public template**, use the initialization script to install what the builds need. Java is the minimum:

```sh
#!/bin/sh
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y default-jre-headless git
```

The script is passed to UpCloud as the server's user data, so it can also be the URL of a script.

On images that use cloud-init, as most current public templates do, Jenkins waits for the script to finish
before it starts the agent, and shows a warning in the agent's log when the script failed. On other images there is
nothing to wait for: the agent starts as soon as Java is available, so install Java last.

With a **custom image** the server is ready much sooner. Set up a server the way you need it, stop it and create a
template from its storage, or build the template with UpCloud's
[Packer plugin](https://github.com/UpCloudLtd/packer-plugin-upcloud). The template then shows up in the **Image**
list, marked *Custom* and with the zone it is stored in. It can only be used for servers in that zone.

## How scaling works

- When builds are waiting for a label, Jenkins asks the cloud for agents. For each one the plugin adds an agent to
  Jenkins right away and then creates its server, waits for it to start, waits for SSH and the initialization script,
  and starts the agent. All of that is logged in the agent's log.
- No more agents are created than the instance caps of the cloud and the template allow.
- An idle agent is removed and its server deleted, together with its storage, when the template says so: after a
  number of idle minutes or just before the billing hour of its server ends.
- An agent that fails to launch, or loses its connection, is removed after 5 minutes. Until then its log and its
  server can be inspected, and its template creates no further servers. This keeps a broken template or an exhausted
  account from creating server after server. Delete the agent to retry right away.
- Agents and their servers survive a restart of the controller, which reconnects to them. They are not removed
  while the controller is down.
- Agents cannot be configured individually. Change the template; agents created from then on pick it up. The page
  of an agent shows which server, zone, plan and image it runs on.
- Remove a cloud only once its agents are gone: an agent needs its cloud's API credentials to delete its server.

### Orphaned servers

Every server the plugin creates is labelled `jenkins-instance=<ID of the controller>`. With **Delete orphaned
servers** enabled, the plugin looks for servers carrying the ID of its own controller every 10 minutes and deletes
those that are older than 10 minutes and do not belong to any agent. This catches servers that could not be deleted
when their agent was removed.

Servers without that label, or with the ID of another controller, are never touched, so the UpCloud account can be
shared. Disable the option on controllers that were cloned from one another: they share the same ID.

### Removing idle agents

A template removes its idle agents in one of two ways:

- **After it has been idle for a while** (the default): the agent is removed once it has not run a build for the
  configured number of minutes.
- **Just before the billing hour of its server ends**: UpCloud bills a server for every hour it starts, so deleting
  a server early saves nothing. With this policy an agent stays available for further builds for the time that is
  paid for anyway, and is removed in the last 5 minutes of an hour if it is idle then. An agent that is building at
  that moment stays for another hour. Hours are counted from the moment the server was created.

In Configuration as Code these are `shutdownPolicy: {idle: {idleMinutes: 10}}` and `shutdownPolicy: "billingHour"`.

### Node properties

Agents cannot be configured individually, so a template lists the node properties its agents get: environment
variables, tool locations, and whatever properties other plugins add.

With the [Scoring Load Balancer](https://plugins.jenkins.io/scoring-load-balancer/) plugin installed this includes
**Preference of Node**. Give the templates different preferences to steer builds towards the agents of one template
rather than another, or towards permanent agents rather than UpCloud agents; a larger value is preferred more. The
preference only counts when the rule *Scoring by Node Preferences* is enabled in that plugin's settings under
**Manage Jenkins » System**. It decides which of the agents that exist gets a build, not which template a new
server is created from: that is the first template of the cloud whose labels match.

## Networking

Each template chooses the networks its servers are attached to:

| Setting | Default | |
| --- | --- | --- |
| Public IPv4 | on | A public IPv4 address. |
| Public IPv6 | off | A public IPv6 address. |
| Utility network | on | UpCloud's private network that connects all servers of an account. |
| SDN private network | none | One of the account's SDN private networks, in the zone of the server. |

**Connect to the agent over** selects the address Jenkins uses for SSH: the public IPv4 address (the default), the
public IPv6 address, the utility network address or the SDN private network address. The server has to be attached
to that network; a template where it is not is rejected before a server is created. The utility and SDN private
network addresses are only reachable from a controller that runs in the same UpCloud account or is otherwise
connected to that network.

A server without a public address needs another way to the internet, such as a NAT gateway on its SDN private
network, for its initialization script and builds.

## Configuration as Code

```yaml
jenkins:
  clouds:
    - upcloud:
        name: "upcloud"
        credentialsId: "upcloud-api"
        instanceCap: 10
        templates:
          - name: "linux"
            labelString: "linux docker"
            zone: "fi-hel1"
            plan: "2xCPU-4GB"
            # UUID of the storage template; public templates have the same UUID in every account.
            image: "01000000-0000-4000-8000-000030240200"
            userData: |
              #!/bin/sh
              set -e
              export DEBIAN_FRONTEND=noninteractive
              apt-get update
              apt-get install -y default-jre-headless git
            sshCredentialsId: "agent-ssh"
            remoteFs: "/home/jenkins"
            shutdownPolicy:
              idle:
                idleMinutes: 10
            instanceCap: 5
```

Further template options: `mode`, `numExecutors`, `storageSizeGb`, `storageTier`, `publicIpv4`, `publicIpv6`, `utilityNetwork`, `privateNetwork`,
`connectionNetwork` (`PUBLIC`, `PUBLIC_IPV6`, `UTILITY` or `PRIVATE`), `sshPort`, `javaPath`, `jvmOptions` and
`startupTimeoutSeconds` and `nodeProperties`, for example:

```yaml
            nodeProperties:
              - buildPreference:
                  preference: 5
```

Further cloud options: `noDelayProvisioning` and `cleanupOrphanedServers`.

## Security notes

- UpCloud does not expose the SSH host key of a new server, so Jenkins cannot verify it on the first connection. The
  key seen then is remembered, and later connections to the same agent must present it.
- The initialization script runs as root and is stored in the Jenkins configuration. Keep secrets out of it.
- Only administrators can configure clouds. Provisioning an agent by hand from the nodes page requires the
  *Cloud/Provision* permission.

## Troubleshooting

- The log of an agent shows every step from creating the server to starting the agent, including the output of
  waiting for the initialization script.
- To see the requests made to the UpCloud API, add a log recorder under **Manage Jenkins » System Log** for the
  logger `com.recranet.jenkins.plugins.upcloud` at level `FINE`. Request and response bodies are not logged.

## Building

```sh
mvn verify          # build, test and static analysis
mvn hpi:run         # start a Jenkins with the plugin at http://localhost:8080/jenkins
```

JDK 17 or 21 and Maven 3.9 are required. The tests run against an in-memory stand-in for the UpCloud API.

## License

[MIT](LICENSE)
