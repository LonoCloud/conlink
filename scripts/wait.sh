#!/bin/sh

# Copyright (c) 2023, Viasat, Inc
# Licensed under MPL 2.0

die() { local ret=${1}; shift; echo >&2 "${*}"; exit $ret; }

NC=$(command -v nc 2>/dev/null)
SOCAT=$(command -v socat 2>/dev/null)
BASH=$(command -v bash 2>/dev/null)
WAIT_SLEEP=${WAIT_SLEEP:-1}

do_sleep() {
  echo "Failed: '${typ} ${arg}'. Sleep ${WAIT_SLEEP} seconds before retry"
  sleep ${WAIT_SLEEP}
}
check_tcp() {
  if   [ "${NC}" ];    then ${NC} -z -w 1 ${1} ${2} > /dev/null
  elif [ "${SOCAT}" ]; then ${SOCAT} /dev/null TCP:${1}:${2},connect-timeout=2
  elif [ "${BASH}" ];  then timeout 1 ${BASH} -c "echo > /dev/tcp/${1}/${2}"
  else die 1 "Could not find nc, socat, or bash"
  fi
}

while [ "${*}" ]; do
  typ="${1}"; shift
  arg="${1}"
  [ "${arg}" = "--" ] && die 2 "No arg found for type '${typ}'"
  case "${typ}" in
    --) break; ;;
    -f|--file)
      while [ ! -e "${arg}" ]; do do_sleep; done
      echo "File '${arg}' exists"
      ;;
    -i|--if|--intf)
      while [ ! -e /sys/class/net/${arg}/ifindex ]; do do_sleep; done
      echo "Interface '${arg}' exists"
      ;;
    -I|--ip)
      while ! grep -qs "^${arg}\>" /proc/net/route; do do_sleep; done
      echo "Interface '${arg}' has IP/routing"
      ;;
    -t|--tcp)
      host=${arg%:*}
      port=${arg##*:}
      [ "${host}" -a "${port}" ] || die 2 "Illegal host/port '${arg}'"
      while ! check_tcp ${host} ${port}; do do_sleep; done
      echo "TCP listener is reachable at '${arg}' "
      ;;
    -u|--umask)
      umask ${arg}
      echo "Set umask to ${arg}"
      ;;
    -c|--cmd|--command)
      while ! ${arg}; do do_sleep; done
      echo "Command successful: ${arg}"
      ;;
    -s|--sleep)
      WAIT_SLEEP=${arg}
      echo "Changed WAIT_SLEEP from ${WAIT_SLEEP} to ${arg}"
      ;;
    *)
      echo "Unknown option: ${typ}"
      exit 1
      ;;
  esac
  shift
done

if [ "${*}" ]; then
  echo "Running: ${*}"
  exec "${@}"
fi
