# Usage Notes

## Asynchronous startup

The conlink managed container links are created after the main process
in the container starts executing. This is different from normal
docker behavior where the interfaces are created and configured before
the main process starts. This means the interfaces for those
links will not be immediately present and the container process will
need to account for this asynchronous interface behavior. The `node`
service in `examples/test2-compose.yaml` shows a simple example of
a container command that will wait for an interface to appear before
continuing with another command.

## System Capabilities/Permissions

The conlink container needs to have a superset of the network related
system capabilities of the containers that it will connect to. At
a minimum `SYS_ADMIN` and `NET_ADMIN` are required but depending on
what the other containers require then those additional capabilities
will also be required for the conlink container. In particular, if the
container uses systemd, then it will likely use `SYS_NICE` and
`NET_BROADCAST` and conlink will likewise need those capabilities.

## Bridging: Open vSwtich/OVS, Linux bridge, and patch

Conlink connects container veth links together via a bridge or via a
direct patch. All veth type links must have a `bridge` property that
defines which links will be connected together (i.e. the same
broadcast domain). The default bridge mode is defined by the
`--default-bridge-mode` parameter and defaults to "auto". If a bridge
is set to mode "auto" then conlink will check if the kernel has the
`openvswitch` kernel module loaded and if so it will create an Open
vSwitch/OVS bridge/switch for that bridge, otherwise it will create a
regular Linux bridge (e.g. brctl). If any bridges are explicitly
defined with an "ovs" mode and the kernel does not have support then
conlink will stop/error on startup.

The "patch" mode will connect two links together using tc qdisc
ingress filters. This type connection is equivalent to a patch panel
("bump-in-the-wire") connection and all traffic will be passed between
the two links unchanged unlike Linux and OVS bridges which typically
block certain bridge control broadcast traffic). The primary downside
of "patch" connections is that they limited to two links whereas "ovs"
and "linux" bridge modes can support many links connected into the
same bridge (broadcast domain).
