#!/bin/bash

# Copyright (c) 2024, Equinix, Inc
# Licensed under MPL 2.0

set -e

usage () {
  echo >&2 "${0} [OPTIONS] <add|del> INTF_A INTF_B PORT_A:IP:PORT_B/PROTO"
  echo >&2 ""
  echo >&2 "Match traffic on INTF_A that has destination port PORT_A and"
  echo >&2 "protocol PROTO (tcp or udp). Forward/DNAT traffic to IP:PORT_B "
  echo >&2 "via INTF_B."
  exit 2
}

info() { echo "link-forward [${LOG_ID}] ${*}"; }

IPTABLES() {
  case "${action}" in
    add) iptables -C "${@}" 2>/dev/null || iptables -I "${@}" ;;
    del) iptables -D "${@}" 2>/dev/null || true;;
  esac
}

action=$1; shift || usage
intf_a=$1; shift || usage
intf_b=$1; shift || usage
spec=$1;   shift || usage
read port_a ip port_b proto <<< "${spec//[:\/]/ }"

[ "${action}" -a "${intf_a}" -a "${intf_b}" ] || usage
[ "${port_a}" -a "${ip}" -a "${port_b}" -a "${proto}" ] || usage

LOG_ID="${spec}"

info "${action^} forwarding ${intf_a} -> ${intf_b}"

IPTABLES PREROUTING -t nat -i ${intf_a} -p ${proto} --dport ${port_a} -j DNAT --to-destination ${ip}:${port_b}
IPTABLES PREROUTING -t nat -i ${intf_a} -p ${proto} --dport ${port_a} -j MARK --set-mark 1
IPTABLES POSTROUTING -t nat -o ${intf_b} -m mark --mark 1 -j MASQUERADE

case "${action}" in
  add) ip route replace ${ip} dev ${intf_b} ;;
  del) ip route delete ${ip} dev ${intf_b} || true;;
esac
