#!/bin/bash

# Copyright (c) 2023, Viasat, Inc
# Licensed under MPL 2.0

set -e

die() { echo "${*}"; exit 1; }
usage () {
  echo "${0} [OPTIONS] INTF0 INTF1 PID0 PID1"
  echo ""
  echo "  INTF0 is the interface in PID0 to move to PID1"
  echo "  INTF1 is the interface in PID1 after moving"
  echo "  PID0 is the process ID of the first namespace"
  echo "  PID1 is the process ID of the second namespace"
  echo ""
  echo "  Where OPTIONS are:"
  echo "     --ipvlan       - Add ipvlan interface to INTF0 and move that instead"
  echo "     --ip IP        - Add IP (CIDR) to the interface"
  echo "     --nat TARGET   - Static NAT (DNAT+SNAT) traffic to/from TARGET"
  exit 2
}

IPTABLES() {
  local ns=${1}; shift
  ip netns exec ${ns} iptables -D "${@}" 2>/dev/null || true
  ip netns exec ${ns} iptables -I "${@}"
}

VERBOSE=${VERBOSE:-}
IPVLAN= IP= TARGET=

# Parse arguments
positional=
while [ "${*}" ]; do
  param=$1; OPTARG=$2
  case ${param} in
  --verbose) VERBOSE=1 ;;
  --ipvlan) IPVLAN=1 ;;
  --ip) IP="${OPTARG}"; shift ;;
  --nat) TARGET="${OPTARG}"; shift ;;
  -h|--help) usage ;;
  *) positional="${positional} $1" ;;
  esac
  shift
done
set -- ${positional}

IF0=$1 IF1=$2 PID0=$3 PID1=$4 NS0=ns${PID0} NS1=ns${PID1}

[ "${VERBOSE}" ] && set -x || true

# Check arguments
[ "${IF0}" -a "${IF1}" -a "${PID0}" -a "${PID1}" ] || usage
[ "${TARGET}" -a -z "${IP}" ] && die "--nat requires --ip"

export PATH=$PATH:/usr/sbin
mkdir -p /var/run/netns
ln -sf /proc/${PID0}/ns/net /var/run/netns/${NS0}
ln -sf /proc/${PID1}/ns/net /var/run/netns/${NS1}

if [ "${IPVLAN}" ]; then
  ip -netns ${NS0} -b - <<EOF
    link add link ${IF0} name tmp$$ type ipvlan mode l2
    link set tmp$$ netns ${NS1} name ${IF1}
EOF
else
  ip -netns ${NS0} link set ${IF0} netns ${NS1} name ${IF1}
fi

ip -netns ${NS1} --force -b - <<EOF
  ${IP:+addr add ${IP} dev ${IF1}}
  link set ${IF1} up
EOF

if [ "${TARGET}" ]; then
  IPTABLES ${NS1} PREROUTING  -t nat -i ${IF1} -j DNAT --to-destination ${TARGET}
  IPTABLES ${NS1} POSTROUTING -t nat -o ${IF1} -j SNAT --to-source ${IP%/*}
fi

# /test/move-link.sh --verbose enp6s0 host 1 2500144 --ipvlan --ip 192.168.88.32/24 --nat 10.0.1.2
