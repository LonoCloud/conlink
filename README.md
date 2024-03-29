# conlink: Declarative Low-Level Networking for Containers


Create (layer 2 and layer 3) networking between containers using
a declarative configuration.

## Prerequisites

General:
* docker
* docker-compose version 1.25.4 or later.

Other:
* For Open vSwtich (OVS) bridging, the `openvswitch` kernel module
  must loaded on the host system (where docker engine is running).
* For podman usage (e.g. second part of `test3`), podman is required.
* For remote connections/links (e.g. `test5`), the `geneve` (and/or
  `vxlan`) kernel module must be loaded on the host system (where
  docker engine is running)
* For CloudFormation deployment (e.g. `test6`), the AWS CLI is
  required.

## Usage Notes

### Asynchronous startup

The conlink managed container links are created after the main process
in the container starts executing. This is different from normal
docker behavior where the interfaces are created and configured before
the main process starts. This means the interfaces for those
links will not be immediately present and the container process will
need to account for this asynchronous interface behavior. The `node`
service in `examples/test2-compose.yaml` shows a simple example of
a container command that will wait for an interface to appear before
continuing with another command.

### System Capabilities/Permissions

The conlink container needs to have a superset of the network related
system capabilities of the containers that it will connect to. At
a minimum `SYS_ADMIN` and `NET_ADMIN` are required but depending on
what the other containers require then those additional capabilities
will also be required for the conlink container. In particular, if the
container uses systemd, then it will likely use `SYS_NICE` and
`NET_BROADCAST` and conlink will likewise need those capabilities.

### Bridging: Open vSwtich/OVS or Linux bridge

Conlink creates bridges/switches and connects veth container links to
a bridge (specified by `bridge:` in the link specification). The
default bridge mode is defined by the `--default-bridge-mode`
parameter and defaults to "auto". If a bridge is set to mode "auto"
then conlink will check if the kernel has the `openvswitch` kernel
module loaded and if so it will create an Open vSwitch/OVS
bridge/switch for that bridge, otherwise it will create a regular
Linux bridge (e.g. brctl). If any bridges are explicitly defined with
an "ovs" mode and the kernel does not have support then conlink will
stop/error on startup.

## Network Configuration Syntax

Network configuration can either be loaded directly from configuration
files using the `--network-config` option or it can be loaded from
`x-network` properties contained in docker-compose files using the
`--compose-file`. Multiple of each option may be specified and all the
network configuration will be merged into a final network
configuration.

The network configuration can have three top level keys: `links`,
`tunnels`, and `commands`.

### Links

Each link defintion specifies an interface that will be configured in
a container. Most types have some sort of connection to either the
conlink/network container or the host network namespace. For example,
"veth" type links always have their peer end connected to a bridge in
the conlink/network container and vlan types are children of physical
interfaces in the host.

The following table describes the link properties:

| property  | link types | format     | default | description              |
|-----------|------------|------------|---------|--------------------------|
| type      | *          | string 1   | veth    | link/interface type      |
| service   | *          | string     | 2       | compose service          |
| container | *          | string     |         | container name           |
| bridge    | veth       | string     |         | conlink bridge / domain  |
| outer-dev | not dummy  | string[15] |         | conlink/host intf name   |
| dev       | *          | string[15] | eth0    | container intf name      |
| ip        | *          | CIDR       |         | IP CIDR (index offset)   |
| mac       | 3          | MAC        |         | MAC addr (index offset)  |
| mtu       | *          | number 4   | 9000    | intf MTU                 |
| route     | *          | string     |         | ip route add args        |
| nat       | *          | IP         |         | DNAT/SNAT to IP          |
| netem     | *          | string     |         | tc qdisc NetEm options   |
| mode      | 5          | string     |         | virt intf mode           |
| vlanid    | vlan       | number     |         | VLAN ID                  |

- 1 - veth, dummy, vlan, ipvlan, macvlan, ipvtap, macvtap
- 2 - defaults to outer compose service
- 3 - not ipvlan/ipvtap
- 4 - max MTU of parent device for \*vlan, \*vtap types
- 5 - macvlan, macvtap, ipvlan, ipvtap

Each link has a 'type' key that defaults to "veth" and each link
definition must also have either a `service` key or a `container` key.
If the link is defined in the service of a compose file then the value
of `service` will default to the name of that service.

The `container` key is a fully qualified container name that this link
will apply to. The `service` key is the name of a docker-compose
service that this link applies to. In the case of a `service` link, if
more than one replica is started for that service, then the mac, and
ip values in the link definition will be incremented by the service
index - 1.

All link definitions support the following optional properties: dev,
ip, mtu, route, nat, netem. If dev is not specified then it will
default to "eth0".  For `*vlan` type interfaces, mtu cannot be larger
than the MTU of the parent (outer-dev) device.

For the `netem` property, refer to the `netem` man page. The `OPTIONS`
grammar defines the valid strings for the `netem` property.

### Bridges

The bridge settings currently only support the "mode" setting. If
the mode is not specified in this section or the section is ommitted
entirely, then bridges specified in the links configuration will
default to the value of the `--default-bridge-mode` parameter (which
itself defaults to "auto").

The following table describes the bridge properties:

| property  | format  | description                    |
|-----------|---------|--------------------------------|
| bridge    | string  | conlink bridge / domain name   |
| mode      | string  | auto, ovs, or linux            |

### Tunnels

Tunnels links/interfaces will be created and attached to the specified
bridge. Any containers with links to the same bridge will share
a broadcast domain with the tunnel link.

The following table describes the tunnel properties:

| property  | format  | description                |
|-----------|---------|----------------------------|
| type      | string  | geneve or vxlan            |
| bridge    | string  | conlink bridge / domain    |
| remote    | IP      | remote host addr           |
| vni       | number  | Virtual Network Identifier |
| netem     | string  | tc qdisc NetEm options     |

Each tunnel definition must have the keys: type, bridge, remote, and
vni. The netem optional property also applies to tunnel interfaces.

### Commands

Commands will be executed in parallel within the matching container
once all links are succesfully configured for that container.

The following table describes the command properties:

| property  | format           | description                |
|-----------|------------------|----------------------------|
| service   | string           | compose service            |
| container | string           | container name             |
| command   | array or string  | command or shell string    |

Each command defintion must have a `command` key and either
a `service` or `container` key. The `service` and `container` keys are
defined the same as for link properties.

If the `command` value is an array then the command and arguments will
be executed directly. If the `command` is a string then the string
will be wrapped in `sh -c STRING` for execution.


## Examples

The examples below require a conlink docker image. Build the image for
both docker and podman like this:

```
docker build -t conlink .
podman build -t conlink .
```

### test1: compose file with embedded network config

Start the test1 compose configuration:

```
docker-compose -f examples/test1-compose.yaml up --build --force-recreate
```

From h1 ping the address of h3 (routed via the r0 container):

```
docker-compose -f examples/test1-compose.yaml exec h1 ping 10.0.0.100
```


### test2: compose file with separate network config and live scaling

Start the test2 compose configuration:

```
docker-compose -f examples/test2-compose.yaml up -d --build --force-recreate
```

From the first node ping the second:

```
docker-compose -f examples/test2-compose.yaml exec --index 1 node ping 10.0.1.2
```

From the second node ping an address in the internet service:

```
docker-compose -f examples/test2-compose.yaml exec --index 2 node ping 8.8.8.8
```

Scale the nodes from 2 to 5 and then ping the fifth node from the second:

```
docker-compose -f examples/test2-compose.yaml up -d --scale node=5
docker-compose -f examples/test2-compose.yaml exec --index 2 node ping 10.0.1.5
```


### test3: network config file only (no compose) and variable templating

#### test3 with docker

Start two containers named `ZZZ_node_1` and `ZZZ_node_2`.

```
docker run --name=ZZZ_node_1 --rm -d --network none alpine sleep 864000
docker run --name=ZZZ_node_2 --rm -d --network none alpine sleep 864000
```

Start the conlink container `ZZZ_network` that will setup a network
configuration that is connected to the other containers:

```
./conlink-start.sh -v --mode docker --host-mode docker --network-file examples/test3-network.yaml -- --name ZZZ_network --rm -e NODE_NAME_1=ZZZ_node_1 -e NODE_NAME_2=ZZZ_node_2
```

In a separate terminal, ping the node 2 from node 1.

```
docker exec -it ZZZ_node_1 ping 10.0.1.2
```

#### test3 with rootless podman

Same as test3 but using rootless podman instead

Start two containers named `ZZZ_node_1` and `ZZZ_node_2`.

```
podman run --name=ZZZ_node_1 --rm -d --network none alpine sleep 864000
podman run --name=ZZZ_node_2 --rm -d --network none alpine sleep 864000
```

Start the conlink container `ZZZ_network` that will setup a network
configuration that is connected to the other containers:

```
./conlink-start.sh -v --mode podman --host-mode podman --network-file examples/test3-network.yaml -- --name ZZZ_network --rm -e NODE_NAME_1=ZZZ_node_1 -e NODE_NAME_2=ZZZ_node_2
```

In a separate terminal, ping the node 2 from node 1.

```
podman exec -it ZZZ_node_1 ping 10.0.1.2
```

### test4: multiple compose files and container commands

Docker-compose has the ability to specify multiple compose files that
are merged together into a single runtime configuration. This test
has conlink configuration spread across multiple compose files and
a separate network config file. The network configuration appears at the
top-level of the compose files and also within multiple compose
service definitions.

Run docker-compose using two compose files. The first defines the
conlink/network container and a basic network configuration that
includes a router and switch (`s0`). The second defines a single
container (`node1`) and switch (`s1`) that is connected to the router
defined in the first compose file.

```
MODES_DIR=./examples/test4-multiple/modes ./mdc node1 up --build --force-recreate
```

Ping the router host from `node1`:

```
docker-compose exec node1 ping 10.0.0.100
```

Restart the compose instance and add another compose file that defines
two node2 replicas and a switch (`s2`) that is connected to the
router.

```
MODES_DIR=./examples/test4-multiple/modes ./mdc node1,nodes2 up --build --force-recreate
```

From both `node2` replicas, ping `node1` across the switches and `r0` router:

```
docker-compose exec --index 1 node2 ping 10.1.0.1
docker-compose exec --index 2 node2 ping 10.1.0.1
```

From `node1`, ping both `node2` replicas across the switches and `r0` router:

```
docker-compose exec node1 ping 10.2.0.1
docker-compose exec node1 ping 10.2.0.2
```


Restart the compose instance and add another compose file that starts
conlink using an addition network file `web-network.yaml`. The network
file starts up a simple web server on the router.

```
MODES_DIR=./examples/test4-multiple/modes ./mdc node1,nodes2,web up --build --force-recreate
```

From the second `node2`, perform a download from the web server running on the
router host:

```
docker-compose exec --index 2 node2 wget -O- 10.0.0.100
```

We can simplify the above launch by using the `all` mode, which contains
depends on `node1`, `nodes2`, and `web`.  Each of those modes depends on
`base`, so there's no need to specify that again (transitive deps).

```
MODES_DIR=./examples/test4-multiple/modes ./mdc all up --build --force-recreate
```

Remove the `.env` file as a final cleanup step:

```
rm .env
```


### test5: conlink on two hosts with overlay connectivity via geneve

Launch a compose instance on host 1 and point it at host 2:

```
echo "REMOTE=ADDRESS_OF_HOST_2" > .env
echo "NODE_IP=192.168.100.1" > .env \
docker-compose --env-file .env -f examples/test5-geneve-compose.yaml up
```

Launch another compose instance on host 2 and point it at host 1:
On host 2 run conlink like this:

```
echo "REMOTE=ADDRESS_OF_HOST_1" > .env
echo "NODE_IP=192.168.100.2" >> .env \
docker-compose --env-file .env -f examples/test5-geneve-compose.yaml up
```

On host 1, start a tcpdump on the main interface capturing Geneve
(encapsulated) traffic:

```
sudo tcpdump -nli eth0 port 6081
```

On host 2, start a ping within the "node1" network namespace created
by conlink:

```
docker-compose -f examples/test5-geneve-compose.yaml exec node ping 192.168.100.1
```

On host 1 you should see bi-directional encapsulated ping traffic on the host.


### test6: conlink on two hosts deployed with CloudFormation

This test uses AWS CloudFormation to deploy two AWS EC2 instances that
automatically install, configure, and start conlink (and dependencies)
using the `test5-geneve-compose.yaml` compose file.

Authenticate with AWS and set the `MY_KEY`, `MY_VPC`, and `MY_SUBNET`
variables to refer to a preexisting key pair name, VPC ID, and Subnet
ID respectively. Then use the AWS CLI to deploy the stack:

```
export MY_KEY=... MY_VPC=... MY_SUBNET=...
aws --region us-west-2 cloudformation deploy --stack-name ${USER}-conlink-test6 --template-file examples/test6-cfn.yaml --parameter-overrides KeyPairName=${MY_KEY} VpcId=${MY_VPC} SubnetId=${MY_SUBNET}
```

The stack will take about 8 minutes to finish deploying. You can
reduce the time to under a minute if you create your own AMI with the
pre-signal steps in `BaseUserData` baked in and modify the template to
use that instead.

Once the stack is finish deploying, show the outputs of the stack
(including instance IP addresses) like this:

```
aws --region us-west-2 cloudformation describe-stacks --stack-name ${USER}-conlink-test6 | jq '.Stacks[0].Outputs'
```

Use ssh to connect to instance 1 and 2 (as the "ubuntu" user), then
sudo to root and cd into `/root/conlink`. You can now run the tcpdump
and ping test described for test5.


### test7: MAC, MTU, and NetEm settings

This example demonstrates using interface MAC, MTU, and NetEm (tc
qdisc) settings.

Start the test7 compose configuration:

```
docker-compose -f examples/test7-compose.yaml up --build --force-recreate
```

Show the links in both node containers to see that the MAC addresses
are `00:0a:0b:0c:0d:0*` and the MTUs are set to `4111`.

```
docker-compose -f examples/test7-compose.yaml exec --index 1 node ip link
docker-compose -f examples/test7-compose.yaml exec --index 2 node ip link
```

Ping the second node from the first to show the the NetEm setting is
adding 40ms delay in both directions (80ms roundtrip).

```
docker-compose -f examples/test7-compose.yaml exec --index 1 node ping 10.0.1.2
```

### test8: Connections to macvlan/vlan host interfaces

This example has two nodes with web servers bound to local addresses.
The first node is connected to a macvlan sub-interfaces of a host
physical interface. The second node is connected to a VLAN
sub-interface of the same host (using VLAN ID/tag 5). Static NAT
(SNAT+DNAT) is setup inside each container to map the external
address/interface to the internal address/interface (dummy) where the
web server is running.

Create an environment file with the name of the parent host interface
and the external IP addresses to assign to each container:

```
cat << EOF > .env
HOST_INTERFACE=enp6s0
NODE1_HOST_ADDRESS=192.168.0.32/24
NODE2_HOST_ADDRESS=192.168.0.33/24
EOF
```

Start the test8 compose configuration using the environment file:

```
docker-compose --env-file .env -f examples/test8-compose.yaml up --build --force-recreate
```

Connect to the macvlan node (NODE1_HOST_ADDRESS) from an external host
on your network (traffic to macvlan interfaces on the same host is
prevented):

```
ping -c1 192.168.0.32
curl 192.168.0.32
```

Note: to connect to the vlan node (NODE2_HOST_ADDRESS) you will need
to configure your physical switch/router with routing/connectivity to
VLAN 5 on the same physical link to your host.

### test9: bridge modes

This example demonstrates the supported bridge modes.

Start the test9 compose configuration:
```
docker-compose -f examples/test9-compose.yaml up --build --force-recreate
```

From the first index of each pair of nodes ping the other node.

```
docker-compose -f examples/test9-compose.yaml exec Ndefault ping 10.0.1.2
docker-compose -f examples/test9-compose.yaml exec Nauto ping 10.0.2.2
docker-compose -f examples/test9-compose.yaml exec Nlinux ping 10.0.3.2
docker-compose -f examples/test9-compose.yaml exec Novs ping 10.0.4.2
```


## GraphViz network configuration rendering

You can use d3 and GraphViz to create a visual graph rendering of
a network configuration. First start a simple web server in the
examples directory:

```
cd examples
python3 -m http.server 8080
```

Use the `net2dot` script to transform a network
configuration into a GraphViz data file (dot language). To render the
network configuration for example test1, run the following in another
window:

```
./conlink --show-config --compose-file examples/test1-compose.yaml | ./net2dot > examples/test1.dot
```

Then load `http://localhost:8080?data=test1.dot` in your browser to see the rendered
image.

The file `examples/net2dot.yaml` contains a configuration that
combines many different configuration elements (veth links, dummy
interfaces, vlan type links, tunnels, etc).

```
./conlink --network-file examples/net2dot.yaml --show-config | ./net2dot > examples/net2dot.dot
```

Then load `http://localhost:8080?data=net2dot.dot` in your browser.


## Copyright & License

This software is copyright Viasat and subject to the terms of the
Mozilla Public License version 2.0 (MPL.20). A copy of the license is
located in the LICENSE file at the top of the repository or available
at https://mozilla.org/MPL/2.0/.

