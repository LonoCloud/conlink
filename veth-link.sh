#!/bin/bash

# Copyright (c) 2021, Viasat, Inc
# Licensed under MPL 2.0

set -e

usage () {
    echo "${0} [OPTIONS] INTF0 INTF1 PID0 PID1"
    echo ""
    echo "  INTF0 is the name of first veth interface"
    echo "  INTF1 is the name of second veth interface name"
    echo "  PID0 is the process ID of the first container"
    echo "  PID1 is the process ID of the second container"
    echo ""
    echo "  Where OPTIONS are:"
    echo "     --ip0 IP0    - IP address"
    echo "     --ip1 IP1    - IP address"
    echo "     --mac0 MAC0  - MAC address"
    echo "     --mac1 MAC1  - MAC address"
    exit 2
}

VERBOSE=${VERBOSE:-}
IP0= IP1= MAC0= MAC1= PID0= PID1=

info() { echo "veth-link [${PID0}/${IF0} <-> ${PID1}/${IF1}] ${*}"; }
warn() { >&2 echo "veth-link [${PID0}/${IF0} <-> ${PID1}/${IF1}] ${*}"; }
die () { warn "ERROR: ${*}"; exit 1; }

# Set MAC, IP, and up state for interface within netns
setup_if() {
  local IF=$1 NS=$2 MAC=$3 IP=$4

  ip -netns ${NS} --force -b - <<EOF
      ${IP:+addr add ${IP} dev ${IF}}
      ${MAC:+link set dev ${IF} address ${MAC}}
      link set dev ${IF} up
      netns del ${NS}
EOF
}

# Parse arguments
positional=
while [ "${*}" ]; do
  param=$1; OPTARG=$2
  case ${param} in
  --verbose) VERBOSE=1 ;;
  --ip0) IP0="${OPTARG}"; shift ;;
  --ip1) IP1="${OPTARG}"; shift ;;
  --mac0) MAC0="${OPTARG}"; shift ;;
  --mac1) MAC1="${OPTARG}"; shift ;;
  -h|--help) usage ;;
  *) positional="${positional} $1" ;;
  esac
  shift
done
set -- ${positional}
IF0=$1 IF1=$2 PID0=$3 PID1=$4

[ "${VERBOSE}" ] && set -x || true

# Check arguments
[ "${IF0}" -a "${IF1}" -a "${PID0}" -a "${PID1}" ] || usage

# Sanity checks
[ ! -d /proc/$PID0 ] && die "PID0 $PID0 is no longer running!"
[ ! -d /proc/$PID1 ] && die "PID1 $PID1 is no longer running!"

### Do the work

info "Creating veth pair link (${IP0}|${MAC0} <-> ${IP1}|${MAC1})"

info "Creating ip netns to pid mappings"
ip netns attach ns${PID0} ${PID0}
ip netns attach ns${PID1} ${PID1}

info "Creating veth pair with ends in each namespace"
ip link add ${IF0} netns ns${PID0} type veth peer ${IF1} netns ns${PID1}

info "Setting MAC, IP, and up state"
setup_if ${IF0} ns${PID0} "${MAC0}" "${IP0}"
setup_if ${IF1} ns${PID1} "${MAC1}" "${IP1}"

info "Created veth pair link (${IP0}|${MAC0} <-> ${IP1}|${MAC1})"
