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

## Copyright & License

This software is copyright Viasat and subject to the terms of the
Mozilla Public License version 2.0 (MPL.20). A copy of the license is
located in the LICENSE file at the top of the repository or available
at https://mozilla.org/MPL/2.0/.

