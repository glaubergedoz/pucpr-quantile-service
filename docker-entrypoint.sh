#!/usr/bin/env sh
set -e

# Generate stubs and compiles Java
protoc -I=proto \
       --java_out=src \
       --grpc-java_out=src \
       proto/quantile_service.proto

CP=$(clj -Spath) 

mkdir -p target/classes
javac -cp "$CP" -d target/classes src/quantile/*.java

# Enter the REPL/server using the alias :dev
exec clj -M:dev
