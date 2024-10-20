# Network Configuration Syntax

Network configuration can either be loaded directly from configuration
files using the `--network-config` option or it can be loaded from
`x-network` properties contained in docker-compose files using the
`--compose-file` option. Multiple of each option may be specified and
all the network configuration will be merged into a final network
configuration. Both options also support colon separated lists.

The network configuration can have four top level keys: `links`,
`bridges`, `tunnels`, and `commands`.

## Links

Each link defintion specifies an interface that will be configured in
a container. Most types have some sort of connection to either the
conlink/network container or the host network namespace. For example,
"veth" type links always have their peer end connected to a bridge in
the conlink/network container and vlan types are children of physical
interfaces in the host.

The following table describes the link properties:

| property  | link types | format         | default | description              |
|-----------|------------|----------------|---------|--------------------------|
| type      | *          | string 1       | veth    | link/interface type      |
| service   | *          | string         | 2       | compose service          |
| container | *          | string         |         | container name           |
| bridge    | veth       | string         |         | conlink bridge / domain  |
| outer-dev | not dummy  | string[15]     |         | conlink/host intf name   |
| dev       | *          | string[15]     | eth0    | container intf name      |
| ip        | *          | CIDR           |         | IP CIDR 7                |
| mac       | 3          | MAC            |         | MAC addr 7               |
| mtu       | *          | number 4       | 65535   | intf MTU                 |
| route     | *          | strings 8      |         | ip route add args        |
| nat       | *          | IP             |         | DNAT/SNAT to IP          |
| netem     | *          | strings 8      |         | tc qdisc NetEm options   |
| mode      | 5          | string         |         | virt intf mode           |
| vlanid    | vlan       | number         |         | VLAN ID                  |
| forward   | veth       | strings 6 8    |         | forward conlink ports 7  |
| ethtool   | veth       | strings 8      |         | ethtool settings         |

- 1 - veth, dummy, vlan, ipvlan, macvlan, ipvtap, macvtap
- 2 - defaults to outer compose service
- 3 - not ipvlan/ipvtap
- 4 - max MTU of parent device for \*vlan, \*vtap types
- 5 - macvlan, macvtap, ipvlan, ipvtap
- 6 - string syntax: `conlink_port:container_port/proto`
- 7 - offset by scale/replica index
- 8 - either a single string or an array of strings

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

The `forward` property is an array of strings that defines ports to
forward from the conlink container into the container over this link.
Traffic arriving on the conlink container's docker interface of type
`proto` and destined for port `conlink_port` is forwarded over this
link to the container IP and port `container_port` (`ip` is required).
The initial port (`conlink_port`) is offset by the service
replica/scale number (minus 1). So if the first replica has port 80
forwarded then the second replica will have port 81 forwarded.
For publicly publishing a port, the conlink container needs to be on
a docker network and the `conlink_port` should match the target port
of a docker published port (for the conlink container).

For the `ethtool` property, refer to the `ethtool` man page. The
syntax for each ethtool setting is basically the ethtool command line
arguments without the "devname. So the equivalent of the ethtool
command `ethtool --offload eth0 rx off` would be link configuration
`{dev: eth0, ethtool: ["--offload rx off"], ...}`.

## Bridges

The bridge settings currently only support the "mode" setting. If
the mode is not specified in this section or the section is omitted
entirely, then bridges specified in the links configuration will
default to the value of the `--default-bridge-mode` parameter (which
itself defaults to "auto").

The following table describes the bridge properties:

| property  | format  | description                    |
|-----------|---------|--------------------------------|
| bridge    | string  | conlink bridge / domain name   |
| mode      | string  | auto, ovs, or linux            |

## Tunnels

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

## Commands

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
