# conlink: Declarative Low-Level Networking for Containers

Create (layer 2 and layer 3) networking between containers using
a declarative configuration.

## Prerequisites

* docker-compose version 1.25.4 or later.
* `openvswitch` kernel module loaded on the host
* `geneve` (and/or `vxlan`) kernel module loaded on the host (only
  needed for `test5-geneve` example)

## Usage Notes

### Asynchronous startup

The container to container links are created after the first process
in the container starts executing. This means the interfaces for those
links will not be immediately present. The container code will need to
account for this asynchronous interface behavior. The `node2` service
in `examples/test2-compose.yaml` shows a simple example of a container
command that will wait for an interface to appear before continuing
with another command.

### System Capabilities/Permissions

The conlink container needs to have a superset of the network related
system capabilities of the containers that it will connect to. At
a minimum `SYS_ADMIN` and `NET_ADMIN` are required but depending on
what the containers require then other capabilities will also be
required. In particular, if the container uses systemd, then it will
likely use `SYS_NICE` and `NET_BROADCAST` and conlink will likewise
need those capabilities.


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

```
sudo nsenter -n -t $(pgrep -f mininet:h1) ping 10.0.0.100
```


### test2: compose file with separate network config file

Start the test2 compose configuration:

```
docker-compose -f examples/test2-compose.yaml up --build --force-recreate
```

From `node1` ping `node2`:

```
docker-compose -f examples/test2-compose.yaml exec node1 ping 10.0.1.2
```

From `node2` ping the "internet" namespace:

```
docker-compose -f examples/test2-compose.yaml exec node2 ping 8.8.8.8
```


### test3: network config file only (no compose)

In terminal 1, start a container named `ZZZ_node`:

```
docker run -it --name=ZZZ_node --rm --cap-add NET_ADMIN --network none alpine sh
```

In terminal 2, start the conlink container `ZZZ_network` that will
setup a network configuration that is connected to the `ZZZ_node`
container:

```
./conlink-start.sh -v --mode docker --host-mode docker --network-file examples/test3-network.yaml -- --name ZZZ_network --rm -e NETWORK_NAME=ZZZ_network -e NODE_NAME=ZZZ_node
```

In terminal 1, ping the `internet` namespace within the network
configuration setup by the `conlink` container.

```
ping 8.8.8.8
```

### test4: rootless podman with network config file

Pull down upstream the podman images that will be used.

```
podman pull docker.io/library/python:3-alpine
podman pull docker.io/library/ubuntu:20.04
```

In terminal 1, start a podman container named `ZZZ_node`:

```
podman run -it --name=ZZZ_node --rm --cap-add NET_ADMIN --network none python:3-alpine sh
```

In terminal 2, start the conlink container `ZZZ_network` that will
setup a network configuration that is connected to the `ZZZ_node`
container:

```
./conlink-start.sh -v --host-mode podman --network-file examples/test4-network.yaml -- --name ZZZ_network --rm -e NETWORK_NAME=ZZZ_network -e NODE_NAME=ZZZ_node
```

In terminal 1 (`ZZZ_node`), ping the `h4` podman container
running inside the conlink podman container.

```
ping 10.0.0.100
```

From the `h1` host, issue an HTTP request to the web server running in
the external `ZZZ_node` container.

```
sudo nsenter -n -t $(pgrep -f mininet:h1) curl 10.0.0.104:84
```

### test5: conlink on two hosts with overlay connectivity via geneve

On host 1 run conlink like this:

```
REMOTE="ADDRESS_OF_HOST_2"
./conlink-start.sh -v --host-mode podman --network-file examples/test5-geneve.yaml -- --rm --publish 6081:6081/udp -e REMOTE=${REMOTE} -e NODE_IP=192.168.100.1
```

On host 2 run conlink like this:

```
REMOTE="ADDRESS_OF_HOST_1"
./conlink-start.sh -v --host-mode podman --network-file examples/test5-geneve.yaml -- --rm --publish 6081:6081/udp -e REMOTE=${REMOTE} -e NODE_IP=192.168.100.2
```

On host 1, start a tcpdump on the main interface capturing Geneve
(encapsulated) traffic:

```
sudo tcpdump -nli eth0 port 6081
```

In a different window on host 1, start tcpdump within the "node1"
network namespace created by conlink:

```
sudo nsenter -n -t $(pgrep -f mininet:node1) tcpdump -nli eth0
```

On host 2, start a ping within the "node1" network namespace created
by conlink:

```
sudo nsenter -n -t $(pgrep -f mininet:node1) ping 192.168.100.1
```

On host 1 you should see encapsulated ping traffic on the host and
encapsulated pings within the "node1" namespace.


### test6: conlink on two hosts deployed with CloudFormation

This test uses AWS CloudFormation to deploy two AWS EC2 instances that
automatically install, configure, and start conlink (and dependencies)
using the `test5-geneve.yaml` network configuration.

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

Use ssh to connect to instance 1 and 2 (as the "ubuntu" user) and then
use nsenter to run tcpdump and ping as described for test5.


### test7: multiple compose files

Docker-compose has the ability to specify multiple compose files that
are merged together into a single runtime configuration. This test
has conlink configuration spread across multiple compose files and
a separate work config file. The network configuration appears at the
top-level of the compose files and also within multiple compose
service definitions.

Run docker-compose using two compose files. The first defines the
conlink/network container and a basic network configuration that
includes a router and switch (`s0`). The second defines a single
container (`node1`) and switch (`s1`) that is connected to the router
defined in the first compose file.

```
COMPOSE_FILE=examples/test7-multiple/base-compose.yaml:examples/test7-multiple/node1-compose.yaml
docker-compose up --build --force-recreate
```

Ping the router host from `node`:

```
docker-compose exec node1 ping 10.0.0.100
```

Restart the compose instance and add another compose file that defines
two more containers (`node2a` and `node2b`) and a switch (`s2`) that
is also connected to the router.

```
COMPOSE_FILE=examples/test7-multiple/base-compose.yaml:examples/test7-multiple/node1-compose.yaml:examples/test7-multiple/nodes2-compose.yaml
docker-compose up --build --force-recreate
```

From `node2a`, ping `node2a` across the switches and router:

```
docker-compose exec node2a ping 10.1.0.1
```

Restart the compose instance and add another compose file that starts
conlink using an addition network file `web-network.yaml`. The network
file starts up a simple web server on the router.

```
COMPOSE_FILE=examples/test7-multiple/base-compose.yaml:examples/test7-multiple/node1-compose.yaml:examples/test7-multiple/nodes2-compose.yaml:examples/test7-multiple/all-compose.yaml
docker-compose up --build --force-recreate
```

From `node2b`, perform a download from the web server running on the
router host:

```
docker-compose exec node2b wget -O- 10.0.0.100
```

### test8: VLAN and MTU settings

This example uses the iproute2 commmands to configure network
interface settings like MTU and VLAN tagging.

Start the test8 compose configuration:

```
docker-compose -f examples/test8-compose.yaml up --build --force-recreate
```

In the network container, start a tcpdump on node1's interface
showing ethernet frame data (including VLAN tags):

```
docker-compose -f examples/test8-compose.yaml exec network tcpdump -nlei node1-eth0
```

From `node1` ping `node2` normally:

```
docker-compose -f examples/test8-compose.yaml exec node1 ping 10.0.1.2
```

From `node2` ping `node1` over the VLAN tagged interface:

```
docker-compose -f examples/test8-compose.yaml exec node2 ping 10.100.0.1
```

### test9: Connections to ipvlan host interfaces

This example has two nodes with web servers bound to local addresses.
Each node is connected to an ipvlan interface on the host. Static NAT
(SNAT+DNAT) is setup inside each container to map the external
address/interface to the internal address/interface where the web
server is running.

Create an environment file with the name of the parent host interface
and the external IP addresses to assign to each container:

```
cat << EOF > .env
HOST_INTERFACE=enp6s0
NODE1_HOST_ADDRESS=192.168.0.32/24
NODE2_HOST_ADDRESS=192.168.0.33/24
EOF
```

Start the test9 compose configuration using the environment file:

```
docker-compose --env-file .env -f examples/test9-compose.yaml up --build --force-recreate
```

Connect to the internal containers from an external host on your
network (traffic between ipvlan interfaces on the same host is
prevented):

```
ping -c1 192.168.0.32
ping -c1 192.168.0.33
curl 192.168.0.32:81
curl 192.168.0.33:82
```


## Copyright & License

This software is copyright Viasat and subject to the terms of the
Mozilla Public License version 2.0 (MPL.20). A copy of the license is
located in the LICENSE file at the top of the repository or available
at https://mozilla.org/MPL/2.0/.

