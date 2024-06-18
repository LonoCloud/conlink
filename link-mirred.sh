#!/bin/bash

# Copyright (c) 2024, Equinix, Inc
# Licensed under MPL 2.0

set -e

usage () {
  echo >&2 "${0} [OPTIONS] INTF0 INTF1"
  echo >&2 ""
  echo >&2 "Create traffic mirror/redirect between INTF0 and INTF1."
  echo >&2 ""
  echo >&2 "Positional arguments:"
  echo >&2 "  INTF0 is the first interface name"
  echo >&2 "  INTF1 is the second interface name"
  echo >&2 ""
  echo >&2 "INTF0 must exist, but if INTF1 is missing, then exit with 0."
  echo >&2 "Each interface will be checked for correct ingress/mirred config"
  echo >&2 "and configured if the configuration is missing."
  echo >&2 "These two aspect make this script idempotent. It can be called"
  echo >&2 "whenever either interface appears and when the second appears,"
  echo >&2 "the mirror/redirect action will be setup fully/bidirectionally."
  echo >&2 ""
  echo >&2 "OPTIONS:"
  echo >&2 "  --verbose                - Verbose output (set -x)"
  exit 2
}

info() { echo "link-mirred [${LOG_ID}] ${*}"; }
warn() { >&2 echo "link-mirred [${LOG_ID}] ${*}"; }
die()  { warn "ERROR: ${*}"; exit 1; }

# Idempotently add ingress qdisc to an interface
add_ingress() {
  local IF=$1 res=

  info "Adding ingress qdisc to ${IF}"
  tc qdisc replace dev "${IF}" ingress \
    || die "Could not add ingress qdisc to ${IF}"
}

# Idempotently add mirred filter redirect rule to an interface
add_mirred() {
  local IF0=$1 IF1=$2 res=

  info "Adding filter redirect action from ${IF0} to ${IF1}"
  tc filter del dev ${IF0} root
  tc filter replace dev ${IF0} parent ffff: protocol all u32 match u8 0 0 action \
    mirred egress redirect dev ${IF1} \
    || die "Could not add filter redirect action from ${IF0} to ${IF1}"
}

# Parse arguments
VERBOSE=${VERBOSE:-}
positional=
while [ "${*}" ]; do
  param=$1; OPTARG=$2
  case ${param} in
  --verbose)        VERBOSE=1 ;;
  -h|--help)        usage ;;
  *)                positional="${positional} $1" ;;
  esac
  shift
done
set -- ${positional}
IF0=$1 IF1=$2

[ "${VERBOSE}" ] && set -x || true

# Sanity check arguments
[ "${IF0}" -a "${IF1}" ] || usage

LOG_ID="mirred ${IF0}:${IF1}"

# Sanity checks
if ! ip link show ${IF0} >/dev/null; then
  die "${IF0} does not exist"
fi
if ! ip link show ${IF1} >/dev/null; then
  info "${IF1} missing, exiting"
  exit 0
fi

### Do the work

info "Creating filter rediction action between ${IF0} and ${IF1}"

add_ingress ${IF0}
add_ingress ${IF1}
add_mirred ${IF0} ${IF1}
add_mirred ${IF1} ${IF0}

info "Created filter rediction action between ${IF0} and ${IF1}"
