FROM node:16 as build

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

FROM node:16-slim as run

RUN apt-get -y update
RUN apt-get -y install libpcap-dev tcpdump iproute2 iputils-ping curl

COPY --from=build /app/ /app/

WORKDIR /app
