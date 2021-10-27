"""
This modules provides 'PodmanHost', which uses podman containers
as mininet network nodes.

For now this invokes the podman command line directly. It does this
because currently there is a bug in podman service API mode
(https://github.com/containers/podman/issues/12059) that prevents the
use DockerHost example (mininet/examples/dockerhost.py) that use
docker API (provided by podman service API) directly.

This is derived from the DockerHost implementation at
https://github.com/mininet/mininet/pull/886/files
"""

from mininet.log import debug, error
from mininet.node import Node
import os
import pty
import select
import shlex
import subprocess
import time

def shlex_or_list(data):
    if type(data) == str:
        return shlex.split(data)
    return data

class PodmanHost(Node):
    """
    This class provides podman containers to be used as host
    in the emulated network.

    The images should have the following packages installed:
    - iproute2
    """

    default_shell_cmd = ["sh", "-is"]
    default_cmd = []
    default_image = "alpine"
    base_general_args = []
    base_run_args = ["--tty", "--detach", "--interactive", "--network=none",
                     "--systemd=always", "--security-opt=apparmor=unconfined",
                     #"--privileged"]
                     "--cap-add=SYS_ADMIN", "--cap-add=NET_ADMIN",
                     "--cap-add=NET_RAW", "--cap-add=NET_BROADCAST",
                     "--cap-add=SYS_NICE", "--cap-add=IPC_LOCK"]

    def __init__(self, name, image=None, cmd=None,
                 general_args=None, run_args=None,
                 shell_cmd=None, **kwargs):
        """
        :param name:         name of this node
        :param image:        docker image to be used, overrides
                             parameter 'image' in 'docker_args'
        :param general_args: additional general podman command line
                             arguments (string).
        :param run_args:     additional podman command line arguments
                             for the podman run command (string).
        :param shell_cmd:    shell process to be started inside container
        """
        self.cmdline = list(self.__class__.default_cmd)
        if cmd is not None:
            self.cmdline = cmd

        self.shell_cmd = list(self.__class__.default_shell_cmd)
        if shell_cmd is not None:
            self.shell_cmd = shlex_or_list(shell_cmd)

        self.general_args = list(self.__class__.base_general_args)
        if general_args is not None:
            self.general_args.extend(shlex_or_list(general_args))

        self.run_args = list(self.__class__.base_run_args)
        if run_args is not None:
            self.run_args.extend(shlex_or_list(run_args))

        self.image = self.__class__.default_image
        if image is not None:
            self.image = image

        self.startContainer(name)

        super(PodmanHost, self).__init__(name, **kwargs)

    def startContainer(self, name=None):
        """
        Start a new container and wait for it to start successfully
        """
        all_args = ["podman"] + self.general_args
        all_args += ["run", "--name=%s" % name] + self.run_args
        all_args += [self.image] + self.cmdline
        debug("all_args: %s\n" % (all_args,))
        cp = subprocess.run(all_args,
                capture_output=True, text=True)
        cp.check_returncode()
        self.cid = cp.stdout.strip()

        cp = subprocess.run(["podman", "ps", "-a"],
                capture_output=True, text=True)
        cp = subprocess.run(["podman", "inspect", self.cid],
                capture_output=True, text=True)

        debug("Waiting for container %s (%s) to start up\n" % (name, self.cid))

        while True:
            cp = subprocess.run(["podman", "inspect", "--format={{.State.Running}}", self.cid],
                capture_output=True, text=True)
            if cp.returncode != 0: break
            if cp.stdout.strip() == "true": break
            time.sleep(0.1)

        cp = subprocess.run(["podman", "inspect", "--format={{.State.Pid}}", self.cid],
                capture_output=True, text=True)
        if cp.returncode != 0:
            print("podman inspect error: %s" % cp.stdout.strip())
            cp.check_returncode()
        self.podman_pid = cp.stdout.strip()

    def startShell(self, mnopts=None):
        if self.shell:
            error("%s: shell is already running\n" % self.name)
            return

        opts = '-cd' if mnopts is None else mnopts
        cmd = [ "mnexec", opts, "-e", self.podman_pid,
                "env", "PS1=" + chr(127) ] + self.shell_cmd

        # Spawn a shell subprocess in a pseudo-tty, to disable buffering
        # in the subprocess and insulate it from signals (e.g. SIGINT)
        # received by the parent
        self.master, self.slave = pty.openpty()
        self.shell = self._popen(
            cmd, stdin=self.slave, stdout=self.slave,
            stderr=self.slave, close_fds=False
        )
        self.stdin = os.fdopen(self.master, 'r')
        self.stdout = self.stdin
        self.pid = self.shell.pid
        self.pollOut = select.poll()
        self.pollOut.register(self.stdout)
        # Maintain mapping between file descriptors and nodes
        # This is useful for monitoring multiple nodes
        # using select.poll()
        self.outToNode[self.stdout.fileno()] = self
        self.inToNode[self.stdin.fileno()] = self
        self.execed = False
        self.lastCmd = None
        self.lastPid = None
        self.readbuf = ''
        # Wait for prompt
        while True:
            data = self.read(1024)
            if data[ -1 ] == chr(127):
                break
            self.pollOut.poll()
        self.waiting = False
        # +m: disable job control notification
        self.cmd('unset HISTFILE; stty -echo; set +m')

    def read(self, *args, **kwargs):
        # The default shell of alpine linux (ash) sends '\x1b[6n' (get
        # cursor position) after PS1, the following code strips all characters
        # after the sentinel chr(127) as a workaround for the inherited
        # functions of class 'Node'
        buf = super(PodmanHost, self).read(*args, **kwargs)
        i = buf.rfind(chr(127)) + 1
        return buf[0:i] if i else buf

    def cmd(self, *args, **kwargs):
        # Using 'ifconfig' for bringing devices up in subprocesses leads
        # to the activation of ax25 network devices on some systems,
        # iproute2 doesn't have this issue, we use the following workaround
        # until mininet is fully refactored to use iproute2
        if args[-1].endswith("up"):
            args = shlex.split(" ".join(args))
            if len(args) == 4:
                args = shlex.split("ip link set " + args[1]
                                   + " up && ip addr add " + args[2]
                                   + " dev " + args[1])
            elif len(args) == 3:
                args = shlex.split("ip link set " + args[1] + " up")
        return super(PodmanHost, self).cmd(*args, **kwargs)

    def terminate(self):
        debug("Removing container %s (%s)\n" % (self.name, self.cid))
        # TODO: not check=True and check the result. Ignore NotFound
        # errors
        cp = subprocess.run(["podman", "rm", "-f", self.cid],
                check=True, capture_output=True, text=True)
        super(PodmanHost, self).terminate()
