#!/bin/sh

# Copyright (c) 2023, Viasat, Inc
# Licensed under MPL 2.0

# Add files from SRC_DIR to DST_DIR with string interpolation.
# Any '{{FOO}}' tokens are replaced with value of corresponding
# environment variable FOO (but only if defined).

die() { local ret=${1}; shift; echo >&2 "${*}"; exit $ret; }

case "${1}" in -T|--template) TEMPLATE=1; shift ;; esac

src_dir="${1}"; shift || die 2 "Usage: ${0} [-T|--template] SRC_DIR DST_DIR"
dst_dir="${1}"; shift || die 2 "Usage: ${0} [-T|--template] SRC_DIR DST_DIR"
[ "${1}" = "--" ] && shift

[ -d "${src_dir}" ] || die 2 "Not a directory: '${src_dir}'"
[ -d "${dst_dir}" ] || die 2 "Not a directory: '${dst_dir}'"

(cd "${src_dir}" && find . -type f) | while read src_file; do
  src="${src_dir}/${src_file}"
  dst="${dst_dir}/${src_file}"
  mkdir -p $(dirname "${dst}") || die 1 "Failed to make target directory"
  echo cp -a "${src}" "${dst}"
  cp -a "${src}" "${dst}" || die 1 "Failed to copy file"
  # TODO: make this configurable
  chown root.root "${dst}" || die 1 "Unable to set ownership"
  chmod +w "${dst}" || die 1 "Unable to make writable"

  [ -z "${TEMPLATE}" ] && continue

  tmpfile="$(mktemp)"
  # match all {{FOO}} style variables and replace from environment
  for v in $(cat "${dst}" | grep -o '{{[^ }{]*}}' | sed 's/[}{]//g' | sort -u); do
    if set | grep -qs "^${v}="; then
      val=$(set | grep "^${v}=" | cut -f 2 -d '=' \
          | sed "s/^['\"]\(.*\)['\"]$/\1/" \
          | sed 's/[\/&]/\\&/g')
      echo "Replacing '{{${v}}}' with '${val}' in '${dst}'"
      sed "s/{{${v}}}/${val}/g" "${dst}" > "${tmpfile}"
      cp "${tmpfile}" "${dst}"
    fi
  done
  rm -f "${tmpfile}"
done

if [ "${*}" ]; then
    exec "${@}"
else
    true
fi
