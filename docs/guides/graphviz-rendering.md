# GraphViz network configuration rendering

You can use d3 and GraphViz to create a visual graph rendering of
a network configuration. First start a simple web server in the
examples directory:

```
cd examples
python3 -m http.server 8080
```

Use the `net2dot` script to transform a network
configuration into a GraphViz data file (dot language). To render the
network configuration for example test1, run the following in another
window:

```
./conlink --show-config --compose-file examples/test1-compose.yaml | ./net2dot > examples/test1.dot
```

Then load `http://localhost:8080?data=test1.dot` in your browser to see the rendered
image.

The file `examples/net2dot.yaml` contains a configuration that
combines many different configuration elements (veth links, dummy
interfaces, vlan type links, tunnels, etc).

```
./conlink --network-file examples/net2dot.yaml --show-config | ./net2dot > examples/net2dot.dot
```

Then load `http://localhost:8080?data=net2dot.dot` in your browser.
