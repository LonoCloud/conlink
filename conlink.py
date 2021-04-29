#!/usr/bin/env -S python3 -u

import argparse, os, re, subprocess, shutil, sys, time
from string import Template
from cerberus import Validator
import config_mininet
import docker
import psutil
import json, yaml

CONFIG_FILE_TEMPLATE = "/var/lib/docker/containers/%s/config.v2.json"

def parseArgs():
    # Parse arguments
    parser = argparse.ArgumentParser(
            description="Container data link networking")
    parser.add_argument('--verbose', '-v', action='count', default=0,
            help="Verbose output")
    parser.add_argument('--network-schema', dest="networkSchema",
            default="/usr/local/share/conlink_schema.yaml",
            help="Network configuration schema")
    parser.add_argument('--network-file', dest="networkFile",
            help="Network configuration file")
    parser.add_argument('--compose-file', dest="composeFile",
            help="Docker compose file")
    parser.add_argument('--profile', action='append', dest="profiles",
            default=[],
            help="Docker compose profile(s)")
    args = parser.parse_args()

    if args.networkFile is None and args.composeFile is None:
        parser.error("either --network-file or --compose-file is required")

    # Allow comma and space delimited within each repeated arg
    args.profiles = [z
            for x in args.profiles
            for y in x.split(' ')
            for z in y.split(',')]

    return args

def envInterpolate(obj, env):
    if isinstance(obj, str):
	# error on lookup failure (use safe_substitute to ignore)
        return Template(obj).substitute(env)
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
    cfg = {}

    for c in [l['left'] for l in links] + [l['right'] for l in links] + cmds:
        # initial state
        cname = c['container']
        cfg[cname] = {
                'cid': None,
                'pid': None,
                'links': [],
                'connected': 0,
                'unconnected': 0,
                'commands': [],
                'commands_completed': False}

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

    return cfg

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
        print("Link {idx}: {name0}/{intf0} -> {name1}/{intf1}".format(**locals()))
        if ctx.verbose >= 2:
            print("    left  PID:       {pid0}".format(pid0=pid0))
            print("    right PID:       {pid1}".format(pid1=pid1))
            print("    left  MAC:       {mac0}".format(mac0=mac0 or "<AUTOMATIC>"))
            print("    right MAC:       {mac1}".format(mac1=mac1 or "<AUTOMATIC>"))
            print("    left  IP:        {ip0}" .format(ip0=ip0 or '<UNSET>'))
            print("    right IP:        {ip1}" .format(ip1=ip1 or '<UNSET>'))

        # Make the veth link
        subprocess.run(linkCmd, check=True)

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

    # Run commands for any fully connected containers
    run_commands(client, containerState)

def run_commands(client, containerState):
    """
    For each container that is fully connected (all links created),
    run commands that are defined for that container.
    """
    for cname, cdata in containerState.items():
        if cdata['commands_completed']:
            # TODO: verbose message
            continue
        if cdata['unconnected'] > 0: continue

        for cmd in containerState[cname]['commands']:
            print("Running command in %s: %s" % (cname, cmd))
            container = client.containers.get(cdata['cid'])
            (_, outstream) = container.exec_run(cmd, stream=True)
            for out in outstream: print(out.decode('utf-8'))

        cdata['commands_completed'] = True

def start(**opts):
    ctx = argparse.Namespace(**opts)

    def vprint(v, *a):
        if ctx.verbose >= v: print(*a)

    vprint(2, "Settings: %s" % opts)

    rawNetworkConfig = None
    if ctx.composeFile:
        vprint(1, "Loading compose file %s" % ctx.composeFile)
        composeConfig = yaml.full_load(open(ctx.composeFile))

        vprint(1, "Determining container ID")
        cgroups = open("/proc/self/cgroup").read()
        myCID = re.search(r"/docker/([^/\n]*)", cgroups).groups()[0]

        vprint(2, "Loading container JSON config")
        myConfig = json.load(open(CONFIG_FILE_TEMPLATE % myCID))
        labels = myConfig["Config"]["Labels"]
        myName = labels['com.docker.compose.service']
        # Create a filter for containers in this docker-compose project
        projectName = labels["com.docker.compose.project"]
        projectWorkDir = labels["com.docker.compose.project.working_dir"]

        myService = composeConfig['services'][myName]
        if 'x-network' in myService and not ctx.networkFile:
            vprint(1, "Using inline x-network config")
            rawNetworkConfig = myService['x-network']

        vprint(2, "myCID:            %s" % myCID)
        vprint(2, "myName:           %s" % myName)
        vprint(2, "myService:        %s" % myService)

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

    vprint(2, "containerPrefix:  %s" % containerPrefix)
    vprint(2, "containerFilter:  %s" % containerFilter)
    vprint(2, "labelFilter:      %s" % labelFilter)

    if ctx.networkFile:
        vprint(1, "Loading network file %s" % ctx.networkFile)
        rawNetworkConfig = yaml.full_load(open(ctx.networkFile))
        rawNetworkConfig = envInterpolate(rawNetworkConfig, os.environ)

    if not rawNetworkConfig:
        print("No network config found.")
        print("Use --network-file or x-network (in %s service)" % myService)
        sys.exit(2)

    print("Validating network configuration")
    vprint(1, "Loading network schema file %s" % ctx.networkSchema)
    netSchema = yaml.full_load(open(ctx.networkSchema))
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

    print("Starting openvswitch service")
    subprocess.run(["/usr/share/openvswitch/scripts/ovs-ctl",
        "start", "--system-id=random"], check=True)

    # Register for docker start events for this docker-compose project
    client = docker.from_env()
    eventIterator = client.events(
            decode=True,
            filters={"event": "start", **labelFilter})

    print("Writing pid to /tmp/setup.pid")
    open("/tmp/setup.pid", 'x').write(str(os.getpid()))

    print("Reached healthy state")

    print("Handling already running containers")
    for c in client.containers.list(sparse=True, filters=labelFilter):
        #print("container: %s, %s" % (c.id, c.attrs))
        handle_container(c.id, client, ctx)

    print("Listening for container start events")
    while True:
        done = True
        for cname, cdata in containerState.items():
            if cdata['unconnected'] > 0: done = False
        if done: break

        event = eventIterator.next()
        #print("event: %s" % event)
        handle_container(event['id'], client, ctx)

    print("Starting mininet")
    vopt = {0: 'info', 1: 'info', 2: 'debug'}[ctx.verbose]
    config_mininet.run(networkConfig, verbose=vopt)


if __name__ == '__main__':
    start(**parseArgs().__dict__)

