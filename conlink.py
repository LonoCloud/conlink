#!/usr/bin/env -S python3 -u

# Copyright (c) 2022, Viasat, Inc
# Licensed under MPL 2.0

import argparse, os
import conlink_inner

def parseArgs():
    # Parse arguments
    parser = argparse.ArgumentParser(
            description="Container data link networking")
    parser.add_argument('--verbose', '-v', action='count', default=0,
            help="Verbose output")
    parser.add_argument('--network-schema', dest="networkSchema",
            default="/usr/local/share/conlink_schema.yaml",
            help="Network configuration schema")
    parser.add_argument('--container-template', dest="containerTemplate",
            default="/var/lib/docker/containers/%s/config.v2.json",
            help="Container configuration file path template")
    parser.add_argument('--network-file', dest="networkFiles",
            default=[], action='extend', type=lambda x: x.split(':'),
            help="Network configuration file")
    parser.add_argument('--compose-file', dest="composeFiles",
            default=[], action='extend', type=lambda x: x.split(':'),
            help="Docker compose file")
    parser.add_argument('--profile', dest="profiles",
            default=[], action='append',
            help="Docker compose profile(s)")
    args = parser.parse_args()

    if args.networkFiles is [] and args.composeFiles is []:
        parser.error("either --network-file or --compose-file is required")

    envv = os.environ.get('CONLINK_VERBOSE') or os.environ.get('VERBOSE')
    if args.verbose == 0 and envv:
        if envv.isnumeric(): args.verbose = int(envv)
        else: args.verbose = 1

    # Allow comma and space delimited within each repeated arg
    args.profiles = [z
            for x in args.profiles
            for y in x.split(' ')
            for z in y.split(',')]

    return args

if __name__ == '__main__':
    conlink_inner.start(**parseArgs().__dict__)
    #try:
    #    start(**parseArgs().__dict__)
    #except Exception as e:
    #    print(e)
    #    while True: time.sleep(10)
