#!/bin/bash

# Copyright (c) 2023, Viasat, Inc
# Licensed under MPL 2.0

set -e

usage () {
    echo >&2 "${0} [OPTIONS] PID INTF"
    echo >&2 ""
    echo >&2 "  INTF is the name of the interface in PID netns"
    echo >&2 "  PID is the process ID of the container"
    echo >&2 ""
    echo >&2 "  Where OPTIONS are:"
    echo >&2 "     --verbose    - Verbose output (set -x)"
    exit 2
}

VERBOSE=${VERBOSE:-}

info() { echo "del-link [${PID}:${IF}] ${*}"; }
warn() { >&2 echo "del-link [${PID}:${IF}] ${*}"; }
die()  { warn "ERROR: ${*}"; exit 1; }

# Parse arguments
positional=
while [ "${*}" ]; do
  param=$1; OPTARG=$2
  case ${param} in
  --verbose) VERBOSE=1 ;;
  -h|--help) usage ;;
  *) positional="${positional} $1" ;;
  esac
  shift
done
set -- ${positional}
PID=$1 IF=$2

NS=ns${PID}

[ "${VERBOSE}" ] && set -x || true

# Check arguments
[ "${IF}" -a "${PID}" ] || usage

# Sanity checks
[ ! -d /proc/$PID ] && die "PID $PID is no longer running!"

### Do the work

info "Deleting link"

info "Creating ip netns to pid mapping"
mkdir -p /var/run/netns
ln -sf /proc/${PID}/ns/net /var/run/netns/ns${PID}

info "Deleting interface"
ip -netns ${NS} link del ${IF}

info "Deleted link"
