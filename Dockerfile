###
### conlink build (ClojureScript)
###
FROM node:20 AS cljs-build

RUN apt-get -y update && \
    apt-get -y install libpcap-dev default-jdk-headless

RUN npm install -g shadow-cljs

# Separate npm and clojure deps from main app build
RUN mkdir -p /app
ADD shadow-cljs.edn package.json /app/
RUN cd /app && npm --unsafe-perm install
RUN cd /app && shadow-cljs info

ADD conlink /app/
ADD src/ /app/src/

# main app build
RUN cd /app && \
    shadow-cljs compile conlink && \
    chmod +x build/*.js

###
### Utilities build (Rust)
###
FROM rust:latest AS rust-build

RUN rustup target add x86_64-unknown-linux-musl

# musl-tools for static linking
RUN apt-get update && apt-get install -y musl-tools

WORKDIR /app/
RUN mkdir -p src

# Download and compile deps for rebuild efficiency
#COPY Cargo.toml Cargo.lock ./
COPY rust/Cargo.toml ./
RUN echo "fn main() {}" > src/echo.rs
RUN cargo build --release --target x86_64-unknown-linux-musl --bin echo
RUN rm src/echo.rs # 1

# Build the main program
COPY rust/src/* src/
RUN cargo build --release --target x86_64-unknown-linux-musl
RUN cd /app/target/x86_64-unknown-linux-musl/release/ && cp -v wait copy echo /app/
# Located at: ./target/x86_64-unknown-linux-musl/release/

###
### conlink runtime stage
###
FROM node:20-slim AS run

RUN apt-get -y update
# Runtime deps and utilities
RUN apt-get -y install libpcap-dev tcpdump iproute2 iputils-ping curl \
                       iptables bridge-utils ethtool jq netcat-openbsd socat \
                       openvswitch-switch openvswitch-testcontroller

RUN mkdir -p /app /utils
COPY --from=cljs-build /app/ /app/
COPY --from=rust-build /app/wait /app/copy /app/echo /utils/
ADD scripts/wait.sh scripts/copy.sh /utils/
ADD link-add.sh link-del.sh link-mirred.sh link-forward.sh /app/
ADD schema.yaml /app/build/

ENV PATH=/app:$PATH
WORKDIR /app
