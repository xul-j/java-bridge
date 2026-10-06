# Build + run environment for the XUL-J Swing bridge (JDK 17 + virtual X display).
FROM eclipse-temurin:17-jdk
RUN apt-get update \
 && apt-get install -y --no-install-recommends xvfb xauth libxrender1 libxtst6 libxi6 fontconfig fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
