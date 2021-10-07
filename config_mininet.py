#!/usr/bin/env python3

# Copyright (c) 2021, Viasat, Inc
# Licensed under MPL 2.0

"""
Load and start a declarative configuration.
"""

import sys, os, time
import json
try:
    import yaml
except:
    yaml = None
import argparse
from mininet.topo import Topo
from mininet.net import Mininet
from mininet.node import ( Node, OVSBridge, Host )
from mininet.link import ( Link, TCIntf )
from mininet.log import setLogLevel, info, debug
from mininet.cli import CLI
from mininet.util import dumpNetConnections

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
            self.addNode(name, **opts)
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


def run(cfg, verbose='info'):
    "Load and start a file configuration"
    setLogLevel( verbose )
    net = ConfigMininet( cfg=cfg )
    net.start()
    debug( '*** Net Values:\n' )
    for node in net.values():
        info( '%s\n' % repr( node ) )
    debug( '*** Net Connections:\n' )
    dumpNetConnections(net)
    if sys.__stdin__.isatty():
        CLI( net )
    else:
        while True: time.sleep(3600)
    net.stop()

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Run mininet using a configuration file')
    parser.add_argument('--verbose', '-v',
            nargs='?', default='info', const='debug',
            help='set verbosity (default: debug)')
    parser.add_argument('config',
            help='path to configuration file')

    args = parser.parse_args()
    topConfig = loadConfig(args.config)
    config = topConfig.get('mininet-cfg', topConfig)
    run(config, verbose=args.verbose)
