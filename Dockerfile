#########################################
# base stage (deps & tools)
#########################################
FROM clojure:openjdk-19-tools-deps AS base
WORKDIR /app

# Install tools on Debian
RUN apt-get update \
&& apt-get install -y --no-install-recommends \
      curl \
      protobuf-compiler \
&& rm -rf /var/lib/apt/lists/*

# Update Clojure CLI to the latest version (with Clojure 1.12+)
RUN curl -sLO https://download.clojure.org/install/linux-install.sh \
&& chmod +x linux-install.sh \
&& ./linux-install.sh \
&& rm linux-install.sh

# Download the gRPC Java plugin (glibc) and mark it executable    
RUN curl -L \
      https://repo1.maven.org/maven2/io/grpc/protoc-gen-grpc-java/1.71.0/protoc-gen-grpc-java-1.71.0-linux-x86_64.exe \
      -o /usr/local/bin/protoc-gen-grpc-java \
&& chmod +x /usr/local/bin/protoc-gen-grpc-java

# Copy Clojure CLI manifests and build
COPY deps.edn build.clj ./

#########################################
# builder stage (generate stubs + uberjar)
#########################################
FROM base AS builder

# Generate Java/GRPC stubs before copying code
COPY proto/ proto/
RUN mkdir -p src
RUN protoc \
      -I=proto \
      --java_out=src \
      --grpc-java_out=src \
      proto/quantile_service.proto

RUN CLASSPATH=$(clj -Spath) \
 && mkdir -p target/classes \
 && find src -name '*.java' > java-sources.txt \
 && javac -cp "$CLASSPATH" -d target/classes @java-sources.txt


# Copy Clojure source code, tests and resources
COPY src/       src/
COPY resources/ resources/
COPY test/ test/

# Run linter and tests. This will fail the build if any test fails.
RUN clj -X:kibit
RUN clj -M:kaocha 

# Generate uberjar with tools.build
RUN clj -T:build clean
RUN clj -T:build compile
RUN clj -T:build uber

#########################################
# dev stage (REPL‑driven with hot‑reload)
#########################################
FROM base AS dev
WORKDIR /app

# Assemble the entire project and expose doors
VOLUME ["/app"]
EXPOSE 8080 7000 50051

# Generate stubs at each start and start REPL
COPY docker-entrypoint.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/docker-entrypoint.sh
ENTRYPOINT ["docker-entrypoint.sh"]


#########################################
# prod stage (lean image with JRE)
#########################################
FROM eclipse-temurin:19-jre-alpine AS prod
WORKDIR /app
COPY --from=builder /app/target/quantile-service-0.1.0.jar ./quantile-service.jar
EXPOSE 8080 50051
ENTRYPOINT ["java", "-jar", "quantile-service.jar"]
