#!/usr/bin/env -S python3 -u

# Copyright (c) 2021, Viasat, Inc
# Licensed under MPL 2.0

import re, sys, yaml

NODE_PROPS = 'shape=box style=filled penwidth=1'
CONTAINER_PROPS = 'fontsize = 12 style = filled fillcolor = "#e1d5e7" color = "#9673a6"'
NETWORK_PROPS = '%s penwidth = 2' % CONTAINER_PROPS
INTF_PROPS = 'width=0.1 height=0.1 fontsize=10 fillcolor="#ffbb9e" color="#d7db00"'
SWITCH_PROPS = 'fontsize=12 style="rounded,filled" fillcolor="#dae8fc" color="#6c8ebf"'
HOST_PROPS = 'fontsize=12 fillcolor="#f5f5f5" color="#666666"'

def id(n):
    return re.sub(r"-", "_", n)

def intfText(cluster, intf):
    name = intf.get('intf', intf.get('origName', intf.get('name')))
    if name.startswith('DUMMY_'):
        return '%s__%s [shape=point style=invis]' % (
                id(cluster), id(name))
    else:
        ip = intf.get('ip', intf.get('opts', {}).get('ip', ''))
        if ip: ip = "\\n%s" % ip
        return '%s__%s [label="%s%s" %s]' % (
                id(cluster), id(name), name, ip, INTF_PROPS)

if __name__ == '__main__':

    networkFile = sys.argv[1]
    networkName = sys.argv[2]

    netCfg = yaml.full_load(open(networkFile))
    if 'services' in netCfg:
        netService = re.sub(r'_1$', '', networkName)
        netCfg = netCfg['services'][netService]['x-network']
    mnCfg = netCfg['mininet-cfg']

    links = []
    containers = {}
    containers[networkName] = {'nodes': []}
    namespaces = {}

    for link in netCfg.get('links', []):
        l, r = link['left'], link['right']
        clname, crname = l['container'], r['container']
        ilname, irname = l['intf'], r['intf']
        links.append([(clname, ilname), (crname, irname)])
        for cname, intf in [(clname, l), (crname, r)]:
            if cname not in containers:
                containers[cname] = {'nodes': []}
            containers[cname]['nodes'].append(intf)

    for lk in mnCfg.get('links', []):
        l, r = lk['left'], lk['right']
        links.append([(networkName, l), (networkName, r)])

    for s in mnCfg.get('switches', []):
        namespaces[s['name']] = {'interfaces': [], 'type': 'switch', **s}
    for h in mnCfg.get('hosts', []):
        namespaces[h['name']] = {'interfaces': [], 'type': 'host', **h}

    for i in mnCfg.get('interfaces', []):
        nm, nd = i.get('origName', i['name']), i['node']
        if nd in namespaces:
            namespaces[nd]['interfaces'].append(i)
        else:
            links.append([(networkName, nm), (networkName, nd)])

    # Make sure all namespaces have at least one interface so that
    # they can be connected by edges (lhead, ltail)
    for nsname, nsdata in namespaces.items():
        if not nsdata['interfaces']:
            nsdata['interfaces'].append({'name': 'DUMMY_%s' % nsname})

    ###

    print('digraph D {')
    print('  splines = true;')
    print('  compound = true;')
    print('  node [%s];' % NODE_PROPS)

    for cname, cdata in containers.items():
        print('  subgraph cluster_%s {' % id(cname))
        print('    label = "%s";' % cname)
        if cname == networkName:
            print('    %s;' % NETWORK_PROPS)
        else:
            print('    %s;' % CONTAINER_PROPS)
        for node in cdata['nodes']:
            print('    %s;' % intfText(node['container'], node))
        # mininet/network interface is processed after container links
        # so that the mininet/network definitions take precedence
        if cname == networkName:
            for nsname, nsdata in namespaces.items():
                print('    subgraph cluster_%s__%s {' % (
                    id(cname), id(nsname)))
                print('      label = "%s";' % nsname)
                if nsdata['type'] == 'switch':
                    print('      %s;' % SWITCH_PROPS)
                else:
                    print('      %s;' % HOST_PROPS)
                for intf in nsdata['interfaces']:
                    print('      %s;' % intfText(cname, intf))
                print('    }')
        print("  }")

    for ((lc, ln), (rc, rn)) in links:
        extra=''
        if lc == networkName and ln in namespaces:
            iname = namespaces[ln]['interfaces'][0]['name']
            extra += ' ltail=cluster_%s__%s' % (id(networkName), id(ln))
            ln = id(iname)
        if rc == networkName and rn in namespaces:
            iname = namespaces[rn]['interfaces'][0]['name']
            extra += ' lhead=cluster_%s__%s' % (id(networkName), id(rn))
            rn = id(iname)
        print('  %s__%s -> %s__%s [dir=none%s];' % (
            id(lc), id(ln), id(rc), id(rn), extra))

    print("}")
