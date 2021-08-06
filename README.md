# conlink: Declarative Low-Level Networking for Containers

Create (layer 2 and layer 3) networking between containers using
a declarative configuration.

## Prerequisites

* `openvswitch` kernel module loaded on the host
* docker-compose version 1.25.4 or later.

## Usage Notes

### Asynchronous startup

The container to container links are created after the first process
in the container starts executing. This means the interfaces for those
links will not be immediately present. The container code will need to
account for this asynchronous interface behavior. The `node` service
in `examples/test2-compose.yaml` shows a simple example of a container
command that will wait for an interface to appear before continuing
with another command.

### System Capabilities/Permissions

The conlink container will need the same level of network related
system capabilities as the containers that it will connect to. At
a minimum `SYS_ADMIN` and `NET_ADMIN` are required but depending on
what the containers require then other capabilities will also be
required. In particular, if the container uses systemd, then it will
likely use `SYS_NICE` and `NET_BROADCAST` and conlink will likewise
need those capabilities.


## Examples

### test1

Start the test1 compose configuration:

```
docker-compose -f examples/test1-compose.yaml up --build --force-recreate
```

```
sudo nsenter -n -t $(pgrep -f mininet:h1) ping 10.0.0.100
```


### test2

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


### test3

In terminal 1, start a container named `ZZZ_node`:

```
docker run -it --name=ZZZ_node --rm --cap-add NET_ADMIN alpine sh
```

In another terminal, start the conlink container `ZZZ_network` that
will setup a network configuration that is connected to the `ZZZ_node`
container:

```
./examples/test3-start.sh ZZZ_network ZZZ_node
```

In terminal 1, ping the `internet` namespace within the network
configuration setup by the `conlink` container.

```
ping 8.8.8.8
```

## Copyright & License

This software is copyright Viasat and subject to the terms of the
Mozilla Public License version 2.0 (MPL.20). A copy of the license is
located in the LICENSE file at the top of the repository or available
at https://mozilla.org/MPL/2.0/.

