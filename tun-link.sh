#!/bin/bash

# Copyright (c) 2021, Viasat, Inc
# Licensed under MPL 2.0

set -e

usage () {
    echo "${0} [OPTIONS] INTF TYPE VNI REMOTE [-- LINK_ARGS]"
    echo ""
    echo "  INTF is the name of the tunnel interface"
    echo "  TYPE is the type of tunnel interface (geneve/vxlan)"
    echo "  VNI is the tunnel Virtual Network Identifier"
    echo "  REMOTE is the IP address of the remote end of the tunnel"
    echo "  LINK_ARGS are extra arguments for the 'ip link' command"
    echo ""
    echo "  Where OPTIONS are:"
    echo "     --verbose       - Enable verbose output"
    echo "     --ip IP         - IP address/CIDR to assign to INTF"
    echo "     --mac MAC       - MAC address to assign to INTF"
    exit 2
}

VERBOSE=${VERBOSE:-}
IP= MAC=

info() { echo "tun-link [${TYPE}/${IF} <-> ${REMOTE}(${VNI})] ${*}"; }
warn() { >&2 echo "tun-link [${TYPE}/${IF} <-> ${REMOTE}(${VNI})] ${*}"; }
die () { warn "ERROR: ${*}"; exit 1; }

# Set MAC, IP, and up state for interface
setup_if() {
  local IF=$1 MAC=$2 IP=$3

  ip --force -b - <<EOF
      ${IP:+addr add ${IP} dev ${IF}}
      ${MAC:+link set dev ${IF} address ${MAC}}
      link set dev ${IF} up
EOF
}

# Parse arguments
positional=
while [ "${*}" ]; do
  param=$1; OPTARG=$2
  case ${param} in
  -v|--verbose) VERBOSE=1 ;;
  --ip) IP="${OPTARG}"; shift ;;
  --mac) MAC="${OPTARG}"; shift ;;
  -h|--help) usage ;;
  --) shift; break ;;
  *) positional="${positional} $1" ;;
  esac
  shift
done
LINK_ARGS="${@}"
set -- ${positional}
IF=$1 TYPE=$2 VNI=$3 REMOTE=$4

[ "${VERBOSE}" ] && set -x || true

# Check arguments
[ "${IF}" -a "${TYPE}" -a "${VNI}" -a "${REMOTE}" ] || usage

# Sanity checks
ip link show ${IF} &>/dev/null && die "${IF} already exists"

### Do the work

info "Creating tunnel interface (${IP}|${MAC})"
ip link add ${IF} type ${TYPE} id ${VNI} remote ${REMOTE} ${LINK_ARGS}

info "Setting MAC, IP, and up state"
setup_if ${IF} "${MAC}" "${IP}"

info "Created veth pair link (${IP}|${MAC})"
