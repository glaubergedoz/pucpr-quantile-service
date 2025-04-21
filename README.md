# Real-Time Quantile Aggregation Service

This project was developed for discipline **DevOps** of course **Information Technology Management** at **PUC-PR**.

## 1. Overview  
This service, implemented in Clojure, exposes REST and gRPC endpoints and processes **millions of events per second** to estimate quantiles (p50, p90, p99, etc.) in **real time**, using **T‑Digest**, **Count‑Min Sketch**, and functional techniques (pure functions + controlled immutability). Each time series consumes only **a few MB** of memory, ensuring high performance and low cost.

## 2. Problem and Objective  
In high‑scale distributed systems (public APIs, microservices, IoT), storing every numeric measurement incurs high cost and query latency. The goals of this project are:

- **Collect** numeric samples from high‑throughput streams.  
- **Maintain** a compact summary (T‑Digest) per key, supporting sliding windows.  
- **Serve** quantile estimates in milliseconds for dashboards and alerts.  

## 3. Use Cases  
1. **Latency Monitoring (APM):** estimating response-time percentiles of services.  
2. **IoT Metrics:** analyzing sensor values in sliding windows.  
3. **Business KPIs:** transaction volume and value on operational dashboards.  

## 4. API Contracts  
### 4.1 gRPC  
```proto
service QuantileService {
  rpc IngestSample    (Sample)           returns (Ack) {}
  rpc QueryQuantile   (QuantileRequest) returns (QuantileResponse) {}
}

message Sample {
  string key       = 1;
  double value     = 2;
  int64  timestamp = 3; // epoch ms
}

message QuantileRequest {
  string key       = 1;
  double q         = 2; // 0.0–1.0
  int32  windowSec = 3; // window in seconds
}

message QuantileResponse {
  double estimate = 1;
  int64  count    = 2; // number of samples in window
}
```

### 4.2 REST (JSON over HTTP/2 or HTTP/1.1)  
- **POST** `/samples` → body as `Sample`.  
- **GET**  `/quantile?key={key}&q={q}&window={windowSec}` → returns  
  ```json
  { "estimate": <value>, "count": <n> }
  ```

## 5. Functional Pipeline in Clojure

### 5.1 Ingestion

- **core.async** `chan` (10 000 buffer) drives samples through a background `go-loop`.  
- **clojure.spec** validates each incoming sample in the loop.  

### 5.2 State per Key

- A single **`atom`** (`states`) maps each `key` to a `StateData`.  
- Each `StateData` contains:  
  - `:cms` → a **Count‑Min Sketch** instance  
  - `:events` → a **vector** of maps `{:value … :timestamp …}`  

### 5.3 Immutable Update + Controlled Mutation

- Pure function **`add-sample->state`** takes existing `StateData` and a sample, returns a new `StateData`.  
- Global `states` atom is updated via `(swap! states update key add-sample->state sample)`.

### 5.4 Sliding Window

- **Window logic** is applied **on read**: `estimate-quantile` filters `:events` by timestamp ≥ now − windowSec.  

### 5.5 Quantile Estimation

- Function **`estimate-quantile [state q windowSec]`**  
  1. Filters events by timestamp cutoff  
  2. Builds a fresh **TDigest**  
  3. Adds each relevant value to the digest  
  4. Returns `{ :estimate digest.quantile(q) :count totalEvents }`

## 6. Algorithms and Data Structures

- **T‑Digest** (`com.tdunning.math.stats.TDigest`) → quantile estimation  
- **Count‑Min Sketch** (`com.clearspring.analytics.stream.frequency.CountMinSketch`) → approximate frequency  
- **Back‑Pressure** via core.async channel buffer of 10 000  

## 7. Scalability & Performance

- **Key sharding** by using independent entries in the `states` atom  
- **Window filtering** done dynamically on each query  

## 8. Clojure Tools and Libraries

- **IO.Pedestal HTTP** (`io.pedestal.http`) for the REST server  
  - `body-params` & `json-body` interceptors for parsing/serializing JSON  
- **gRPC Java** + `io.grpc.netty.shaded` + generated stubs + `proxy` implementation  
- **`mount.core`** for lifecycle management of ingest loop, HTTP server, and gRPC server  
- **`clojure.core.async`** for asynchronous ingestion  
- **`clojure.spec.alpha`** for request validation  
- **`com.tdunning/t-digest`** for TDigest  
- **`com.clearspring.analytics/stream`** for Count‑Min Sketch  
- **`clojure.tools.build`** (`build.clj`) for compiling stubs, Clojure code, and generating an uberjar  
- **REPL helpers** in `dev/user.clj` (`start!`, `stop!`, `restart!`)  
- **Error logging** via `clojure.spec/explain-str` in case of invalid samples  







## 9. Usage Instructions

### System Requirements

- **Docker Engine**
- **Docker Compose**
- `git` (optional, for cloning the repo)

### Development Environment Setup

- **Clone the repo** (or unpack the project folder).
    ```bash
    git clone https://your‑repo/pucpr-quantile‑service.git
    cd pucpr-quantile‑service
    ```
- **Build** the dev container.
    ```bash
    docker compose build --no-cache dev
    ```
- **Run** the container in dev mode.
    ```bash
    dock compose run -i --service-ports dev
    ```
- **Start** the service:
    At `user=>`:
    ```bash
    (start!)
    ```
    You should see:
    ```bash
    [mount] Starting ingest loop
    [mount] Starting HTTP server on port 8080
    [mount] Starting gRPC server on port 50051
    Quantile service running on HTTP:8080 and gRPC:50051
    ```
    **That's it!**   
    The endpoints should be available on ports 8080 (HTTP) e 50051 (gRPC).

- **Test the HTTP** endpoints.  
    
    Ingest a sample:
    ```bash
    TIMESTAMP_MS=$(($(date +%s)*1000))
    ```
    ```bash
    curl -i -X POST http://localhost:8080/samples \
            -H 'Content-Type: application/json' \
            -d '{
                "key": "foo",
                "value": 42.0,
                "timestamp": '"$TIMESTAMP"'
            }'
    ```

    **Expected HTTP 200** with JSON body, e.g.:
    ```json
    {
        "ack": true,
        "ingestedAt": 1682001234567
    }
    ```

    Query a Quantile:
    ```bash
    curl -i -G http://localhost:8080/quantile \
    --data-urlencode "key=foo" \
    --data-urlencode "q=0.5" \
    --data-urlencode "window=60"
    ```

    **Possible responses**:

    - **200 OK** if data exists:
        ```json
        {
            "estimate": {
                "estimate": 42.0,
                "count": 1
            }
        }
        ```

    - **404 Not Found** if key missing:
        ```json
        { "error": "Key not found" }
        ```

    - **400 Bad Request** on invalid params:
        ```json
        { "error": "Missing key, q or window" }
        ```

- **Test the gRPC** endpoints. Pay attention to the `-proto` flag, which needs to point to the correct path of the file and this depends on the current directory where you will execute the `grpcurl` command.  

    Install [`grpcurl`](https://github.com/fullstorydev/grpcurl) or use any gRPC client.

    IngestSample:
    ```bash
    TIMESTAMP_MS=$(($(date +%s)*1000))
    ```
    ```bash
    grpcurl -plaintext \
        -proto pucpr-quantile-service/proto/quantile_service.proto \
        -d '{"key":"foo","value":42.0,"timestamp":'"$TIMESTAMP_MS"'}' \
        localhost:50051 \
        quantile.QuantileService/IngestSample
    ```

    **Response**:
    ```json
        { "success": true }
    ```

    QueryQuantile:
    ```bash
    grpcurl -plaintext \
        -proto pucpr-quantile-service/proto/quantile_service.proto \
        -d '{"key":"foo","q":0.5,"windowSec":60}' \
        localhost:50051 \
        quantile.QuantileService/QueryQuantile
    ```

    **Response**:
    ```json
    {
        "estimate": 42.0,
        "count": 1
    }
    ```

### Production Environment

- **Build** the prod image:
    ```bash
    docker-compose build prod
    ```

- **Run** the container in prod mode:
    ```bash
    docker-compose up -d prod
    ```
    The application JAR file will be generated and should start the services on ports `8080` (HTTP) and `50051` (gRPC).
    
#### Testing Production Jar

Use **exactly the same** HTTP and gRPC commands from previuos sections to verify the running jar.
