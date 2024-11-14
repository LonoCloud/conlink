# Examples

The [examples](https://github.com/LonoCloud/conlink/tree/master/examples)
directory contains the necessary files to follow along below.

The examples also require a conlink docker image. Build the image for both
docker and podman like this:

```
docker build -t conlink .
podman build -t conlink .
```

## test1: compose file with embedded network config

Start the test1 compose configuration:

```
docker-compose -f examples/test1-compose.yaml up --build --force-recreate
```

From h1 ping the address of h3 (routed via the r0 container):

```
docker-compose -f examples/test1-compose.yaml exec h1 ping 10.0.0.100
```


## test2: compose file with separate network config and live scaling

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


## test3: network config file only (no compose) and variable templating

### test3 with docker

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

### test3 with rootless podman

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

## test4: multiple compose files and container commands

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


## test5: conlink on two hosts with overlay connectivity via geneve

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


## test6: conlink on two hosts deployed with CloudFormation

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


## test7: MAC, MTU, and NetEm settings

This example demonstrates using interface MAC, MTU, and NetEm (tc
qdisc) settings.

Start the test7 compose configuration:

```
docker-compose -f examples/test7-compose.yaml up --build --force-recreate
```

Show the links in both node containers to see that on the eth0
interfaces the MAC addresses are `00:0a:0b:0c:0d:0*` and the MTUs are
set to `4111`. The eth1 interfaces should have the command line set
default MTU of `5111`.

```
docker-compose -f examples/test7-compose.yaml exec --index 1 node ip link
docker-compose -f examples/test7-compose.yaml exec --index 2 node ip link
```

Ping the second node from the first to show the the NetEm setting is
adding 40ms delay in both directions (80ms roundtrip).

```
docker-compose -f examples/test7-compose.yaml exec --index 1 node ping 10.0.1.2
```

## test8: Connections to macvlan/vlan host interfaces

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

## test9: bridge modes

This example demonstrates the supported bridge modes.

Start the test9 compose configuration using different bridge modes and
validate connectivity using ping:

```
export BRIDGE_MODE="linux"  # "ovs", "patch", "auto"
docker-compose -f examples/test9-compose.yaml up --build --force-recreate
docker-compose -f examples/test9-compose.yaml exec node1 ping 10.0.1.2
```

## test10: port forwarding and routing

This example demonstrates port forwarding from the conlink container
to two containers running simple web servers. It also demonstrates the
use of a router container and multiple route rules in the other
containers.

Start the test10 compose configuration:

```
docker-compose -f examples/test10-compose.yaml up --build --force-recreate
```

Ports 3080 and 8080 are both published on the host by the conlink
container using standard Docker port mapping. The internal mapping of
those ports (1080 and 1180 respectively) are both are forwarded to
port 80 in the node1 container using conlink's port forwarding
mechanism. The two paths look like this:

```
host:3080 --> 1080 (in conlink) --> node1:80
host:8080 --> 1180 (in conlink) --> node1:80
```

Use curl on the host to query both of these paths to node1:

```
curl 0.0.0.0:3080
curl 0.0.0.0:8080
```

Ports 80 and 81 are published on the host by the conlink container
using standard Docker port mapping. Then conlink forwards from ports
80 and 81 to the first and second replica (respectively) of node2,
each of which listen internally on port 80. The two paths look like
this:

```
host:80 -> 80 (in conlink) -> node2_1:80
host:81 -> 81 (in conlink) -> node2_2:80
```

Use curl on the host to query both replicas of node2:

```
curl 0.0.0.0:80
curl 0.0.0.0:81
```

Start a two tcpdump processes in the conlink container to watch
routed ICMP traffic and then ping between containers across the router
container:

```
docker compose -f examples/test10-compose.yaml exec network tcpdump -nli router_1-es1 icmp
docker compose -f examples/test10-compose.yaml exec network tcpdump -nli router_1-es2 icmp
```

```
docker-compose -f examples/test10-compose.yaml exec node1 ping 10.2.0.1
docker-compose -f examples/test10-compose.yaml exec node1 ping 10.2.0.2
docker-compose -f examples/test10-compose.yaml exec node2 ping 10.1.0.1

```

## test11: copy/wait tools

This example demonstrates the use of the `copy` and `wait` commands.
The `copy` command recursively copies and templates files/directories.
The `wait` command waits/blocks on certain file and network
events/states. Refer to the guide for more information about these
programs.

Start the test11 compose configuration:

```
docker-compose -f examples/test11-compose.yaml up --build --force-recreate
```

The `webserver` service uses `copy` to populate and
template a file hierarchy that it will serve. The `webserver` and
`client` services both use `wait` to wait for conlink
interfaces to be configured before continuing. Finally the `client`
service also uses `wait` to probe the `webserver` until it accepts
TCP connections on port 8080 before, and then it starts its main loop
that repeatedly requests a templated file from the `webserver`.
