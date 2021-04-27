# conlink: Declarative Low-Level Networking for Containers

Create (layer 2 and layer 3) networking between containers using
a declarative configuration.


## Examples

### test1

Start the test1 compose configuration:

```
docker-compose -f test1-compose.yaml up --build --force-recreate
```

```
sudo nsenter -n -t $(pgrep -f mininet:h1) ping 10.0.0.100
```


### test2

Start the test2 compose configuration:

```
docker-compose -f test2-compose.yaml up --build --force-recreate
```

From `node1` ping `node2`:

```
docker-compose -f test2-compose.yaml exec node1 ping 10.0.1.2
```

From `node2` ping the "internet" namespace:

```
docker-compose -f test2-compose.yaml exec node2 ping 8.8.8.8
```


