# Compose scripts

Conlink also includes scripts that make docker compose a much more
powerful development and testing environment:

* `mdc` - modular management of multiple compose configurations
* `wait.sh` - wait for network and file conditions before continuing
* `copy.sh` - recursively copy files with variable templating

## mdc

The `mdc` command adds flexibility and power to the builtin overlay
capability of docker compose. Docker compose can specify multiple
compose files that will be combined into a single configuration.
Compose files that are specified later will overlay or override
earlier compose files. For example, if compose files A and B are
loaded by docker compose, then the `image` property of a service in
file B will take precedence (or override) the `image` property for the
same service in file A. Some properties such as `volumes` and
`environment` will have the sub-properties merged or appended to.

There are several ways that `mdc` adds to the composition capabilities
of docker compose:
1. **Mode/module dependency resolution**. The modes or modules that
   are combined by `mdc` are defined as directories that contain
   mode/module specific content. A `deps` file in a mode/module
   directory is used to specify dependencies on other modes/modules.
   The syntax and resolution algorithm is defined by the
   [resolve-deps](https://github.com/Viasat/resolve-deps) project.
2. **Environment variable file combining/overlaying**. Each `.env`
   file that appears in a mode/module directory will be appended into
   a single `.env` file at the top-level where the `mdc` command is
   invoked. Later environment variables will override earlier ones
   with the same name. Variable interpolation and some shell-style
   variable expansion can be used to combine/append environment
   variables. For example if FOO and BAR are defined in an earlier
   mode/module, then BAZ could be defined like this:
   `BAZ="${FOO:-${BAR}-SUFF"` which will set BAZ to FOO if FOO is set,
   otherwise, it will set BAZ to BAR with a "-SUFF" suffix.
3. **Directory hierarchy combining/overlaying**. If the mode/module
   directory has subdirectories that themselves contain a "files/"
   sub-directory, then the mode subdirectories will be recursively
   copied into the top-level ".files/" directory. For example,
   consider if the following files exists under the modes "foo" and
   "bar" (with a dependency of "bar" on "foo"):
   `foo/svc1/files/etc/conf1`, `foo/svc2/files/etc/conf2`, and
   `bar/svc1/files/etc/conf1`. When `mdc` is run this will result in
   the following two files: `.files/svc1/etc/conf1` and
   `.files/svc2/etc/conf2`. The content of `conf1` will come from the
   "bar" mode because it is resolved second. The use of the `copy.sh`
   script (described below) simplifies recursive file copying and also
   provides variable templating of copied files.
4. **Set environment variables based on the selected modes/modules**.
   When `mdc` is run it will set the following special environment
   variables in the top-level `.env` file:
   * `COMPOSE_FILE`: A colon separated and dependency ordered list of
     compose file paths from each resolved mode/module directory.
   * `COMPOSE_DIR`: The directory where the top-level `.env` is
     created.
   * `COMPOSE_PRPOFILES`: A comma separated list of each resolved
     mode/module with a `MODE_` prefix on the name. These are docker
     compose profiles that can be used to enable services in one
     mode/module compose file when a different mode/module is
     selected/resolved by `mdc`. For example, if a compose file in
     "bar" has a service that should only be enabled when the "foo"
     mode/module is also requested/resolved, then the service can be
     tagged with the `MODE_foo` profile.
   * `MDC_MODE_DIRS`: A comma separated list of mode/module
     directories. This can be used by other external tools that have
     specific mode/module behavior.

Conlink network configuration can be specified in `x-network`
properties within compose files. This can be a problem with the
builtin overlay functionality of docker compose because `x-` prefixed
properties are simply overriden as a whole without any special merging
behavior. To work around this limitation, conlink has the ability to
directly merge `x-network` configuration from multiple compose files
by passing the `COMPOSE_FILE` variable to the conlink `--compose-file`
parameter (which supports a colon sperated list of compose files).

## wait.sh

The dynamic event driven nature of conlink mean that interfaces may
appear after the container service code starts running (unlike plain
docker container networking). For this reason, the `wait.sh` script is
provided to simplify waiting for interfaces to appear (and other
network conditions). Here is a compose file snippit that will wait for
`eth0` to appear and for `eni1` to both appear and have an IP address
assigned before running the startup command (after the `--`):

```
services:
  svc1:
    volumes:
      - ./conlink/scripts:/scripts:ro
    command: /scripts/wait.sh -i eth0 -I eni1 -- /start-cmd.sh arg1 arg2
```

In addition to waiting for interfaces and address assignment,
`wait.sh` can also wait for a file to appear (`-f FILE`), a remote TCP
port to become accessible (`-t HOST:PORT`), or run a command until it
completes successfully (`-c COMMAND`).


## copy.sh

One of the features of the `mdc` command is to collect directory
hierarchies from mode/module directories into a single `.files/`
directory at the top-level. The intended use of the merged directory
hierarchy is to be merged into file-systems of running containers.
However, simple volume mounts will replace entire directory
hierarchies (and hide all prior files under the mount point). The
`copy.sh` script is provided for easily merging/overlaying one
directory hierarchy onto another one. In addition, the `-T` option
will also replace special `{{VAR}}` tokens in the files being copied
with the value of the matching environment variable.

Here is a compose file snippit that shows the use of `copy.sh` to
recursively copy/overlay the directory tree in `./.files/svc2` onto
the container root file-system. In addition, due to the use of the
`-T` option, the script will replace any occurence of the string
`{{FOO}}` with the value of the `FOO` environment variable within any
of the files that are copied:

```
services:
  svc2:
    environment:
      - FOO=123
    volumes:
      - ./.files/svc2:/files:ro
    command: /scripts/copy.sh -T /files / -- /start-cmd.sh arg1 arg2
```

Note that instances of `copy.sh` and `wait.sh` can be easily chained
together like this:
```
/scripts/copy.sh -T /files / -- /scripts/wait.sh -i eth0 -- cmd args
```
