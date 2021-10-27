#!/usr/bin/env python3

# Copyright (c) 2021, Viasat, Inc
# Licensed under MPL 2.0

"""
Load and start a declarative mininet configuration.
"""

import sys
import json
import traceback
try:
    import yaml
except:
    yaml = None
from mininet.topo import Topo
from mininet.net import Mininet
from mininet.node import ( OVSBridge, Host )
# TODO: when/if https://github.com/containers/podman/issues/12059
#       is fixed then switch to mininet.examples.dockerhost.DockerHost
from podmanhost import PodmanHost
from mininet.link import ( Link, TCIntf )
from mininet.log import info, debug

# TCLink in mininet 2.2.2 is buggy (and doesn't pass params correctly
# to the Link class). Provide our own based on the version at
# https://github.com/mininet/mininet/commit/31f44e1856b5581514af84c94d46021ed3961571
class TCLink( Link ):
    "Link with TC interfaces"
    def __init__( self, *args, **kwargs):
        kwargs.setdefault( 'cls1', TCIntf )
        kwargs.setdefault( 'cls2', TCIntf )
        Link.__init__( self, *args, **kwargs)

class ConfigTopo( Topo ):
    "A topology loaded from a configuration file."

    def build( self, cfg=None, **kwargs ):
        for switch in cfg.get('switches', {}):
            name, opts = switch['name'], switch.pop('opts', {})
            self.addSwitch(name, **opts)
        for host in cfg.get('hosts', {}):
            name, opts = host['name'], host.pop('opts', {})
            self.addHost(name, cls=Host, **opts)
        for container in cfg.get('containers', {}):
            name, opts = container['name'], container.pop('opts', {})
            self.addHost(name, cls=PodmanHost, **opts)
        for link in cfg.get('links', []):
            left, right = link['left'], link.pop('right')
            opts = link.pop('opts', {})
            self.addLink(left, right, **opts)

class ConfigMininet( Mininet ):
    def __init__( self, cfg, *args, **kwargs):
        self.cfg = cfg
        topo = ConfigTopo(cfg=self.cfg)
        kwargs.setdefault( 'topo', topo )
        kwargs.setdefault( 'switch', OVSBridge )
        kwargs.setdefault( 'host', Host )
        kwargs.setdefault( 'link', TCLink )
        Mininet.__init__( self, *args, **kwargs)

        self.addInterfaces()

    def start(self, *args, **kwargs):
        Mininet.start( self, *args, **kwargs)

        self.runCommands()

    def addInterfaces(self):
        interfaces = self.cfg.get('interfaces', {})
        if interfaces: info( '*** Adding external interfaces:\n' )
        for intf in interfaces:
            name, nodeName = intf['name'], intf['node']
            opts = intf.pop('opts', {})
            origName = intf.pop('origName', name)
            node = self[nodeName]
            info( '  %s->%s:%s\n' % (origName, nodeName, name) )
            intf = TCIntf(origName, node=node, **opts)
            if name != origName:
                intf.rename(name)

    def runCommands(self):
        commands = self.cfg.get('commands', [])
        if commands: info( '*** Running commands:\n' )
        for spec in commands:
            node = self[spec['node']]
            sync = spec.get('sync', False)
            if list == type(spec['command']):
                cmds = [c.rstrip() for c in spec['command'] ]
            else:
                cmds = [ spec['command'].rstrip() ]
            for cmd in cmds:
                if not sync and cmd[-1] != '&':
                    cmd += ' &'
                info( '  %s: %s\n' % (spec['node'], cmd) )
                node.cmd(cmd)


def loadConfig(cfg):
    if isinstance(cfg, dict):
        rawCfg = cfg
    else:
        if cfg.endswith('.yaml') or cfg.endswith('.yml'):
            if not yaml:
                raise Exception('YAML file but no python-yaml module')
            rawCfg = yaml.full_load(open(cfg))
        else:
            rawCfg = json.load(open(cfg))
    return convertToUnicode(rawCfg)

# From mininet/examples/miniedit.py:convertJsonUnicode
def convertToUnicode(text):
    "Some part of Mininet don't like Unicode"
    if (sys.version_info > (3, 0)): return text
    if isinstance(text, dict):
        return {convertToUnicode(key): convertToUnicode(value) for key, value in text.items()}
    elif isinstance(text, list):
        return [convertToUnicode(element) for element in text]
    elif isinstance(text, unicode):
        return text.encode('utf-8')
    else:
        return text

