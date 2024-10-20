# conlink: Declarative Low-Level Networking for Containers

Create (layer 2 and layer 3) networking between containers using
a declarative configuration.

Conlink also includes scripts that make docker compose a much more
powerful development and testing environment (refer to
[Compose scripts](https://lonocloud.github.io/conlink/#/guides/compose-scripts) for
details):

* [mdc](https://lonocloud.github.io/conlink/#/guides/compose-scripts?id=mdc): modular management of multiple compose configurations
* [wait.sh](https://lonocloud.github.io/conlink/#/guides/compose-scripts?id=waitsh): wait for network and file conditions before continuing
* [copy.sh](https://lonocloud.github.io/conlink/#/guides/compose-scripts?id=copysh): recursively copy files with variable templating

## Why conlink?

There are a number of limitations of docker-compose networking that
conlink addresses:

* Operates at layer 3 of the network stack (i.e. IP).
* Has a fixed model of container interface naming: first interface is
  `eth0`, second is `eth1`, etc.
* For containers with multiple interfaces, the mapping between docker
  compose networks and container interface is not controllable
  (https://github.com/docker/compose/issues/4645#issuecomment-701351876,
  https://github.com/docker/compose/issues/8561#issuecomment-1872510968)
* If a container uses the scale property, then IPs cannot be
  assigned and user assigned MAC addresses will be the same for every
  instance of that service.
* Docker bridge networking interferes with switch and bridge protocol
  traffic (BPDU, STP, LLDP, etc). Conlink supports the "patch" link
  mode that allows this type of traffic to pass correctly.

Conlink has the following features:

- Declarative network configuration (links, bridges, patches, etc)
- Event driven (container restarts and scale changes)
- Low-level control of network interfaces/links: MTU, routes, port
  forwarding, netem properties, etc
- Automatic IP and MAC address incrementing for scaled containers
- Central network container for easy monitoring and debug
- Composable configuration from multiple sources/locations

## Prerequisites

General:
* docker
* docker-compose version 1.25.4 or later.

Other:
* For Open vSwtich (OVS) bridging, the `openvswitch` kernel module
  must loaded on the host system (where docker engine is running).
* For patch connections (`bridge: patch`), the kernel must support
  tc qdisc mirred filtering via the `act_mirred` kernel module.
* For podman usage (e.g. second part of `test3`), podman is required.
* For remote connections/links (e.g. `test5`), the `geneve` (and/or
  `vxlan`) kernel module must be loaded on the host system (where
  docker engine is running)
* For CloudFormation deployment (e.g. `test6`), the AWS CLI is
  required.

## Copyright & License

This software is copyright Viasat and subject to the terms of the
Mozilla Public License version 2.0 (MPL.20). A copy of the license is
located in the LICENSE file at the top of the repository or available
at https://mozilla.org/MPL/2.0/.
