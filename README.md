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

## 9. Quality Assurance

### Tools & Benefits

- **Kaocha**  
  An advanced Clojure test runner that organizes unit, integration, load and end‑to‑end suites.  
  - Readable reports, tag support, parallel execution, and optional coverage reporting via plugins (e.g. `kaocha-cloverage`).  
  - Makes it easy to group and filter tests by type, ensuring each layer is validated in isolation.
  - Documentation [here](https://github.com/lambdaisland/kaocha).

- **Kibit**  
  A static code analyzer for Clojure that suggests idiomatic refactorings and stylistic improvements.  
  - Helps maintain consistent style and avoid common “code smells.”  
  - Simple integration via `clj -X:kibit`, failing the build if any warnings are emitted.
  - Documentatoin [here](https://github.com/clj-commons/kibit).

### CI/CD Automation

All tests and lint checks run automatically in the Docker/CI pipeline. Any failure in lint or tests immediately aborts the build.


## 10. Usage Instructions

### System Requirements

- **Docker Engine**
- **Docker Compose**
- `git` (optional, for cloning the repo)


### Basic Usage (Local Production Environment)

- **Clone the repo** (or unpack the project folder).
    ```bash
    git clone https://your‑repo/pucpr-quantile‑service.git
    ```
- **Access the project root folder**
    ```bash
    cd pucpr-quantile‑service
    ```

- **Build** the prod image:
    ```bash
    docker compose build --no-cache prod
    ```

- **Run** the container in prod mode:
    ```bash
    docker compose up -d prod
    ```
    **That's it!!** 🚀🚀🚀  
    The application JAR file will be generated and should start the services on ports `8080` (HTTP) and `50051` (gRPC).  
    Now, you can [test the endpoints](#testing-the-endpoints).

### Development Environment Setup

- **Clone the repo** (or unpack the project folder).
    ```bash
    git clone https://your‑repo/pucpr-quantile‑service.git
    ```
- Access the **project root folder**
    ```bash
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
    Now, you can [test the endpoints](#testing-the-endpoints).

- Run the **linter**:
    ```bash
    clj -X:kibit
    ```

- Run the **unit tests**:
    ```bash
    clj -M:kaocha :unit
    ```

- Run the **integration tests**:
    ```bash
    clj -M:kaocha :integration
    ```

### Using the Endpoints

- **HTTP Endpoints**  
    
    Ingest a sample:
    ```bash
    TIMESTAMP_MS=$(($(date +%s)*1000))
    ```
    ```bash
    curl -X POST http://localhost:8080/samples \
            -H 'Content-Type: application/json' \
            -d '{
                "key": "foo",
                "value": 42.0,
                "timestamp": "$TIMESTAMP_MS"
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

- **gRPC Services**  
    Pay attention to the `-proto` flag, which needs to point to the correct path of the file and this depends on the current directory where you will execute the `grpcurl` command.  

    Install [`grpcurl`](https://github.com/fullstorydev/grpcurl) or use any other gRPC client.

    IngestSample:
    ```bash
    TIMESTAMP_MS=$(($(date +%s)*1000))
    ```
    ```bash
    grpcurl -plaintext \
        -proto proto/quantile_service.proto \
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
        -proto proto/quantile_service.proto \
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


## 11. CI/CD Pipeline

This project leverages **GitHub Actions** to automate both continuous integration and continuous deployment. The workflow is defined in `.github/workflows/ci-cd.yml` and includes the following stages:

1. **Checkout & Setup**  
   - Checks out the repository.  
   - Installs JDK 19 and the Clojure CLI.

2. **Linting**  
   - Runs `clj -X:kibit` to enforce idiomatic Clojure and fails the build on any warnings.

3. **Testing**  
   - Executes the full Kaocha test suite (unit, integration, load, and end-to-end) inside the Docker **builder** stage.  
   - Aborts immediately if any test fails.

4. **Docker Build**  
   - Builds a multi-stage Docker image using the provided `Dockerfile`.  
   - **Base** stage installs dependencies and tools; **builder** stage compiles and generates the uberjar; **prod** stage produces a lean runtime image with just the JRE and the jar.

5. **Image Tag & Push**  
   - Tags the image with the Git commit SHA (e.g. `quantile-service:${{ github.sha }}`).  
   - Pushes the image to Docker Hub registry.

6. **Deployment**  
   - Uses the AWS CLI (configured via GitHub Secrets) to update the running service on AWS.  
   - Performs a rolling deployment so that new containers replace old ones without downtime.

All steps are configured to **fail fast**, ensuring only lint- and test-verified artifacts make it to production.

## 12. Production Environment on AWS (EKS)

The service runs in a Kubernetes-managed environment on **Amazon EKS**, with fully automated deployments via GitHub Actions.

  - Deployed into a dedicated VPC across multiple Availability Zones for high availability.  
  - Uses managed node groups to run pods.  
  - Ingress traffic is handled by a Kubernetes Ingress, routing HTTP (port 8080) and gRPC (port 50051) requests to the `quantile-service` Deployment.
  - IAM permissions for the service account are scoped via an AWS IAM Role for Service Accounts (IRSA) to limit access to only required AWS resources.


This EKS-based setup ensures your quantile aggregation service is highly available, scalable, and continuously delivered from code push through to production without manual intervention.  

