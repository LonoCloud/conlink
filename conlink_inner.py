#!/usr/bin/env -S python3 -u

# Copyright (c) 2023, Viasat, Inc
# Licensed under MPL 2.0

import argparse, os, re, subprocess, shlex, shutil, sys, time
from functools import reduce
from compose_interpolation import TemplateWithDefaults
from cerberus import Validator
import docker
import psutil
import json, yaml

def envInterpolate(obj, env):
    if isinstance(obj, str):
	# error on lookup failure (use safe_substitute to ignore)
        return TemplateWithDefaults(obj).substitute(env)
    if isinstance(obj, dict):
        return {k: envInterpolate(v, env) for k, v in obj.items()}
    if isinstance(obj, list):
        return [envInterpolate(v, env) for v in obj]
    return obj

def initContainerState(networkConfig):
    """
    Transform networkConfig into a object indexed by container name
    (service_index), with a flattened command list, and with initial
    container state.
    """
    links = networkConfig['links']
    cmds = networkConfig.get('commands', [])
    intfs = networkConfig.get('interfaces', [])
    cfg = {}

    for c in [l['left'] for l in links] + [l['right'] for l in links] + cmds + intfs:
        # initial state
        cname = c['container']
        cfg[cname] = {
                'cid': None,
                'pid': None,
                'links': [],
                'connected': 0,
                'unconnected': 0,
                'commands': [],
                'commands_completed': False,
                'interfaces': [],
                'interfaces_completed': False}

    for idx, link in enumerate(links):
        link['index'] = idx
        link['connected'] = False
        for node in (link['left'], link['right']):
            cname = node['container']
            cfg[cname]['links'].append(link)
            cfg[cname]['unconnected'] += 1

    for cmd in cmds:
        cname = cmd['container']
        # Each 'command' is a singleton or a list. Flatten it.
        if type(cmd['command']) == list:
            cfg[cname]['commands'].extend(cmd['command'])
        else:
            cfg[cname]['commands'].append(cmd['command'])

    for intf in intfs:
        cname = intf['container']
        cfg[cname]['interfaces'].append(intf)

    return cfg

# Simplified version of the compose merge process.
# Enough to extract profiles, scale, and x-network properties
# https://docs.docker.com/compose/extends/#adding-and-overriding-configuration
def configMerge(a, b, depth=3, path=""):
    a = a.copy()
    for k, v in b.items():
        npath = path + "/" + k
        #print("path:", npath, "depth:", depth, "type(v):", type(v))
        if not k in a:
            a[k] = v
        elif k == 'x-network':
            a[k] = configMerge(a[k], b[k], depth=1, path=npath)
        elif k == 'mininet-cfg':
            a[k] = configMerge(a[k], b[k], depth=1, path=npath)
        elif type(v) == list:
            a[k] = a[k] + v
        elif type(v) == dict:
            if depth == 0:
                a[k] = b[k]
            else:
                a[k] = configMerge(a[k], b[k], depth-1, path=npath)
        else:
            a[k] = v
    return a
def configMerge1(a, b): return configMerge(a, b, depth=1, path="")
def configMerge3(a, b): return configMerge(a, b, depth=3, path="")

def getComposeContainers(composeConfig, profiles, prefix):
    containers = []
    for sname, sdata in composeConfig['services'].items():
        sprofiles = sdata.get('profiles', [])
        if sprofiles and not (set(profiles) & set(sprofiles)):
            continue
        for idx in range(1, sdata.get('scale', 1)+1):
            containers.append("%s%s_%s" % (prefix, sname, idx))
    return containers

def mangleNetworkConfig(netCfg, containers, prefix):
    """
    - Rewrite network config to use actual container names instead of
      the convenience aliases.
    - Prune links, interfaces, and commands based on enabled service
      profiles.
    """
    # Prune/rewrite links
    links = []
    intfs = []
    for link in netCfg.get('links', []):
        l, r = link['left'], link['right']
        lname, rname = prefix+l['container'], prefix+r['container']
        if not containers or (lname in containers and rname in containers):
            link['left']['container'] = lname
            link['right']['container'] = rname
            links.append(link)
            intfs.extend([l['intf'], r['intf']])
    netCfg['links'] = links

    # Prune/rewrite host interfaces
    host_intfs = []
    for intf in netCfg.get('interfaces', []):
        cname = prefix+intf['container']
        if not containers or (cname in containers):
            intf['container'] = cname
            host_intfs.append(intf)
    netCfg['interfaces'] = host_intfs

    # Tunnel interfaces are always configured
    for tunnel in netCfg.get('tunnels', []):
        intfs.append(tunnel['intf'])

    # Prune/rewrite interfaces
    interfaces = []
    for interface in netCfg['mininet-cfg'].get('interfaces', []):
        iname = interface.get('origName', interface.get('name'))
        if iname in intfs:
            interfaces.append(interface)
    netCfg['mininet-cfg']['interfaces'] = interfaces

    # Prune/rewrite commands
    commands = []
    for command in netCfg.get('commands', []):
        cname = prefix+command['container']
        if not containers or (cname in containers):
            command['container'] = cname
            commands.append(command)
    netCfg['commands'] = commands

    return netCfg

def create_tunnels(tunnels, ctx):
    for tunnel in tunnels:
        ttype, intf = tunnel['type'], tunnel['intf']
        vni, remote = tunnel['vni'], tunnel['remote']

        mac, ip  = tunnel.get('mac'), tunnel.get('ip')
        link_args = tunnel.get('link_args')
        if type(link_args) == str: link_args = shlex.split(link_args)

        tunCmd = ["/sbin/tun-link.sh", intf, ttype, str(vni), remote]
        if mac:       tunCmd.extend(["--mac", mac])
        if ip:        tunCmd.extend(["--ip",  ip])
        if link_args: tunCmd.extend(link_args)

        env = {}
        print("Tunnel {ttype}: {intf} -> {remote}({vni})".format(**locals()))
        if ctx.verbose >= 2:
            print("    MAC:       {mac}" .format(mac=mac or "<AUTOMATIC>"))
            print("    IP:        {ip}"  .format(ip=ip or '<UNSET>'))
            print("    LINK_ARGS: {args}".format(args=link_args or '<UNSET>'))
            env = {"VERBOSE": "1"}

        # Make the tunnel interface/link
        subprocess.run(tunCmd, check=True, env=env)

def veth_span(name0, ctx):
    """
    The container name0 is now up. For each container name1 that name0
    has a connection link to, if name1 is also up then run the
    veth-link.sh command to create the veth connections between the
    two containers.
    """
    containerState = ctx.containerState
    for link in containerState[name0]['links']:
        if link['connected']:
            # TODO: verbose message
            continue
        if link['left']['container'] == name0:
            link0, link1 = link['left'], link['right']
        elif link['right']['container'] == name0:
            link0, link1 = link['right'], link['left']
        else:
            continue
        name1 = link1['container']
        pid0 = containerState[name0]['pid']
        pid1 = containerState[name1]['pid']
        if not pid1: continue
        intf0, intf1 = link0['intf'],      link1['intf']
        mac0,  mac1  = link0.get('mac'),   link1.get('mac')
        ip0,   ip1   = link0.get('ip'),    link1.get('ip')

        linkCmd = ["/sbin/veth-link.sh", intf0, intf1, str(pid0), str(pid1)]
        if mac0: linkCmd.extend(["--mac0", mac0])
        if mac1: linkCmd.extend(["--mac1", mac1])
        if ip0:  linkCmd.extend(["--ip0",  ip0])
        if ip1:  linkCmd.extend(["--ip1",  ip1])

        idx = link['index']
        env = {}
        print("Link {idx}: {name0}/{intf0} -> {name1}/{intf1}".format(**locals()))
        if ctx.verbose >= 2:
            print("    left  PID:       {pid0}".format(pid0=pid0))
            print("    right PID:       {pid1}".format(pid1=pid1))
            print("    left  MAC:       {mac0}".format(mac0=mac0 or "<AUTOMATIC>"))
            print("    right MAC:       {mac1}".format(mac1=mac1 or "<AUTOMATIC>"))
            print("    left  IP:        {ip0}" .format(ip0=ip0 or '<UNSET>'))
            print("    right IP:        {ip1}" .format(ip1=ip1 or '<UNSET>'))
            env = {"VERBOSE": "1"}

        # Make the veth link
        subprocess.run(linkCmd, check=True, env=env)

        # Accounting
        link['connected'] = True

        for cname in (name0, name1):
            cfg = containerState[cname]
            cfg['connected'] += 1
            cfg['unconnected'] -= 1
            connected, unconnected = cfg['connected'], cfg['unconnected']

            state = unconnected > 0 and "partially" or "fully"
            print("Container %s is %s connected (%s/%s links)" % (
                cname, state, connected, unconnected+connected))

def move_interfaces(ctx):
    for name, cState in ctx.containerState.items():
        if cState['interfaces_completed']: continue
        if cState['unconnected'] > 0: continue
        for intf in cState['interfaces']:
            type = intf['type']
            host_intf, intf_name = intf['host-intf'], intf['intf']
            mode, vlanid = intf.get('mode'), intf.get('vlanid')
            ip, nat = intf.get('ip'), intf.get('nat')
            pid = cState['pid']

            moveCmd = ["/sbin/move-intf.sh", type, host_intf, intf_name, '1', str(pid)]
            if mode:   moveCmd.extend(["--mode", mode])
            if vlanid: moveCmd.extend(["--vlanid", str(vlanid)])
            if ip:     moveCmd.extend(["--ip",  ip])
            if nat:    moveCmd.extend(["--nat", nat])

            env = {}
            print("Interface {type}: {host_intf} -> {name}/{intf_name}".format(**locals()))
            if ctx.verbose >= 2:
                print("    mode:            {mode}".format(mode=mode))
                print("    vlanid:          {vlanid}".format(vlanid=vlanid))
                print("    ip:              {ip}".format(ip=ip))
                print("    nat:             {nat}".format(nat=nat))
                env = {"VERBOSE": "1"}

            # Make the veth link
            subprocess.run(moveCmd, check=True, env=env)

        # Accounting
        cState['interfaces_completed'] = True

def run_commands(client, ctx):
    """
    For each container that is fully connected (all links created),
    run commands that are defined for that container.
    """
    for cname, cState in ctx.containerState.items():
        if cState['commands_completed']: continue
        if cState['unconnected'] > 0: continue

        for cmd in cState['commands']:
            print("Running command in %s: %s" % (cname, cmd))
            container = client.containers.get(cState['cid'])
            (_, outstream) = container.exec_run(cmd, stream=True)
            for out in outstream: print(out.decode('utf-8'))

        cState['commands_completed'] = True

def handle_container(cid, client, ctx):
    """
    Register the container ID and parent process PID in the
    containerState, then call veth_span to setup veth links with any
    other started containers that are also connected to this
    container.
    """
    containerState = ctx.containerState
    pid = None
    try:
        container = client.containers.get(cid)
        cname = container.attrs['Name']
        if cname not in containerState:
            if ctx.verbose >= 1:
                print("No config for '%s' (%s), ignoring" % (cname, pid))
            return

        _pid = container.attrs['State']['Pid']
        if psutil.pid_exists(_pid):
            pid = _pid
        else:
            # If first process fork'd/exec'd then find first still
            # running pid in the container
            if ctx.verbose >= 1:
                print("Process %s (State.Pid) gone, trying top()" % _pid)
            for _, _pid in [p[1] for p in container.top()['Processes']]:
                if psutil.pid_exists(_pid):
                    pid = _pid
                    break
    except docker.errors.APIError as err:
        if err.status_code != 409: raise
    if not pid:
        print("Service %s exited, ignoring (container: %s)" % (service, cid))
        return

    print("Container '%s' (%s) is running" % (cname, pid))
    containerState[cname]['cid'] = cid
    containerState[cname]['pid'] = pid

    veth_span(cname, ctx)

    # Move interfaces and run commands for fully connected containers
    move_interfaces(ctx)
    run_commands(client, ctx)


def start(**opts):
    """
    opts values:
    - verbose:           verbosity level (0, 1, or 2)
    - networkSchema:     path to conlink/config_mininet schema
    - containerTemplate: templated path to conlink container config
    - networkFiles:      network configuration/spec files
    - composeFiles:      compose files (with conlink container)
    - profiles:          compose profiles to use
    """
    ctx = argparse.Namespace(**opts)

    def vprint(v, *a):
        if ctx.verbose >= v: print(*a)

    def read_inode(inode):
        with open(inode) as f:
            return f.read()

    def get_container_id():
        cgroups = read_inode("/proc/self/cgroup")
        cid_cgroups = re.search(r"/docker/([^/\n]*)", cgroups)
        if cid_cgroups:
            return cid_cgroups.groups()[0]

        mountinfo = read_inode("/proc/self/mountinfo")
        cid_mountinfo = re.search(r"/containers/([^/\n]*)", mountinfo)
        if cid_mountinfo:
            return cid_mountinfo.groups()[0]

        vprint(1, f'Cgroups content: \n {cgroups}')
        vprint(1, f'Mountinfo content: \n {mountinfo}')
        raise Exception('Container ID could not be identified!')

    vprint(1, "Settings: %s" % opts)

    rawNetworkConfigs = []
    if ctx.composeFiles:
        vprint(0, "Loading compose files: %s" % ctx.composeFiles)
        composeConfigs = [yaml.full_load(open(f)) for f in ctx.composeFiles]
        composeConfig = reduce(configMerge3, composeConfigs)

        vprint(1, "Determining container ID")
        myCID = get_container_id()

        vprint(1, "Loading container JSON config")
        myConfig = json.load(open(ctx.containerTemplate % myCID))
        labels = myConfig["Config"]["Labels"]
        # Create a filter for containers in this docker-compose project
        projectName = labels["com.docker.compose.project"]
        projectWorkDir = labels["com.docker.compose.project.working_dir"]

        if 'x-network' in composeConfig:
            rawNetworkConfigs.append(composeConfig['x-network'])
        for serviceData in composeConfig['services'].values():
            if 'x-network' in serviceData:
                rawNetworkConfigs.append(serviceData['x-network'])
        if len(rawNetworkConfigs) > 0:
            vprint(1, "Using inline x-network config")

        vprint(1, "myCID:            %s" % myCID)

        containerPrefix = '/%s_' % projectName
        containerFilter = getComposeContainers(
                composeConfig, ctx.profiles, containerPrefix)
        labelFilter = {"label": [
            "com.docker.compose.project=%s" % projectName,
            "com.docker.compose.project.working_dir=%s" % projectWorkDir
        ]}

    else:
        containerPrefix = '/'
        containerFilter = None
        labelFilter = {}

    vprint(1, "containerPrefix:  %s" % containerPrefix)
    vprint(1, "containerFilter:  %s" % containerFilter)
    vprint(1, "labelFilter:      %s" % labelFilter)

    for networkFile in ctx.networkFiles:
        vprint(0, "Loading network file %s" % networkFile)
        rawNetworkConfigs.append(yaml.full_load(open(networkFile)))

    if len(rawNetworkConfigs) == 0:
        print("No network config specified.")
        print("Use --network-file or 'x-network' in compose file")
        sys.exit(2)

    # env var interpolation like docker-compose (e.g. with defaults)
    rawNetworkConfig = reduce(configMerge1, rawNetworkConfigs)
    rawNetworkConfig = envInterpolate(rawNetworkConfig, os.environ)

    vprint(0, "Loading network schema file %s" % ctx.networkSchema)
    netSchema = yaml.full_load(open(ctx.networkSchema))
    vprint(0, "Validating network configuration")
    v = Validator(netSchema)
    # TODO: assure no references to non-existent container names
    if not v.validate(rawNetworkConfig):
        print("Network config parsing errors:")
        print(json.dumps(v.errors, indent=2))
        sys.exit(2)

    #print("rawNetworkConfig:\n%s" % json.dumps(rawNetworkConfig, indent=2))
    networkConfig = mangleNetworkConfig(
            rawNetworkConfig, containerFilter, containerPrefix)
    #print("networkConfig:\n%s" % json.dumps(networkConfig, indent=2))

    containerState = initContainerState(networkConfig)
    ctx.containerState = containerState
    #print("containerState:\n%s" % json.dumps(containerState, indent=2))

    ######

    while True:
        mods = subprocess.run(["lsmod"], check=True, stdout=subprocess.PIPE)
        if re.search(r"^openvswitch\b", mods.stdout.decode('utf-8'),
                re.MULTILINE):
            break
        print("ERROR: openvswitch kernel module is missing on the host")
        print("       load the module (modprobe openvswitch) to continue")
        time.sleep(5)

    vprint(0, "Starting openvswitch service")
    subprocess.run(["/usr/share/openvswitch/scripts/ovs-ctl",
        "start", "--system-id=random", "--no-mlockall"], check=True)

    # TODO: when/if https://github.com/containers/podman/issues/12059
    #       is fixed then switch to using API instead of calling
    #       podman directly
    # vprint(0, "Starting podman service")
    # os.makedirs("/var/run/podman", exist_ok=True)
    # subprocess.Popen(["/usr/bin/podman", "system", "service",
    #     "--time=0", "unix:///var/run/podman/podman.sock"],
    #     env={"CONTAINERS_CONF": "/etc/containers/containers.conf",
    #          "PATH": os.environ['PATH']})

    # Register for docker start events for this docker-compose project
    vprint(1, "Registering for events from host docker/podman")
    os.environ['DOCKER_HOST'] = "unix:///var/run/docker.sock"
    client = docker.from_env()
    eventIterator = client.events(
            decode=True,
            filters={"event": "start", **labelFilter})

    vprint(0, "Writing pid to /var/run/conlink.pid")
    open("/var/run/conlink.pid", 'x').write(str(os.getpid()))

    vprint(0, "Reached healthy state")

    if 'tunnels' in networkConfig:
        vprint(0, "Creating tunnel interfaces")
        create_tunnels(networkConfig['tunnels'], ctx)

    vprint(0, "Handling already running containers")
    for c in client.containers.list(sparse=True, filters=labelFilter):
        #print("container: %s, %s" % (c.id, c.attrs))
        handle_container(c.id, client, ctx)

    vprint(0, "Listening for container start events")
    while True:
        done = True
        for cname, cdata in containerState.items():
            if cdata['unconnected'] > 0: done = False
        if done: break

        event = eventIterator.next()
        #print("event: %s" % event)
        handle_container(event['id'], client, ctx)
    vprint(0, "All container are connected")

    # vprint(1, "Waiting for podman service to start")
    # for i in range(5):
    #     if os.path.exists("/var/run/podman/podman.sock"): break
    #     time.sleep(1)
    # if not os.path.exists("/var/run/podman/podman.sock"):
    #     raise Exception("podman service did not start in 5 second")
    # os.environ['DOCKER_HOST'] = "unix:///var/run/podman/podman.sock"

    vprint(0, "Starting mininet")
    # TODO: fix resource complaint trigged by this code:
    # https://github.com/mininet/mininet/blob/dad451bf8fc8f9d0527b4a10b875660ac30e8b8b/mininet/util.py#L512
    # TODO: use secure temp file naming
    with open("/tmp/config.yaml", 'w') as file:
        yaml.dump(networkConfig, file)
    cm_cmd = ["/sbin/config_mininet"]
    if ctx.verbose >= 2: cm_cmd.append("--verbose=debug")
    subprocess.run(cm_cmd + ["/tmp/config.yaml"])

