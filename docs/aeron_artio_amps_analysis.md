# Low-Latency Trading Architecture Analysis: Aeron, Artio, and 60East AMPS

This document provides a comprehensive structural analysis of building an ultra-low-latency FIX routing layer using **Aeron**, **Artio (Aeron-FIX)**, and **60East AMPS**.

---

## 1. Aeron Off-Heap Memory and FIX Architecture

**Aeron itself does not include a built-in FIX engine**, but it is highly optimized to run FIX engines entirely **off-heap** when combined with the right companion libraries. 

Because Aeron's core architecture relies on memory-mapped files and off-heap ring buffers (via Agrona), low-latency systems use it alongside specific off-heap FIX implementations to guarantee zero-Garbage Collection (GC) processing paths.

### How an Off-Heap FIX Engine Works with Aeron

1. **Agrona Buffers**: Aeron projects utilize **Agrona** (a companion sub-project) to handle off-heap memory allocations. An off-heap FIX engine leverages Agrona’s `DirectBuffer` implementations to parse and map raw byte arrays directly from incoming network buffers without creating temporary Java objects.
2. **Zero-Copy Parsing & SBE**: Rather than turning raw FIX strings into high-overhead Java objects on the JVM heap, low-latency architectures parse text-based FIX fields directly in-place or transcode them into **Simple Binary Encoding (SBE)** schemas. SBE reads and writes variables directly out of off-heap memory regions mapped by Aeron.
3. **Artio (The De Facto Aeron FIX Engine)**: When developers build Aeron-native trading systems, they typically use **Artio** (formerly known as Aeron-FIX), which is an open-source, ultra-low-latency FIX engine designed specifically by the same performance-minded ecosystem. Artio runs intentionally **off-heap**, allocates zero garbage on the hot path, and natively couples with Aeron for session management, replication, and archival.

---

## 2. Structural Performance: Artio vs. QuickFIX/J

While standalone, publicly visible benchmark suites are tightly guarded by proprietary trading firms, the architectural profiles of both engines demonstrate a definitive performance divergence.

| Metric / Feature | QuickFIX/J | Artio |
| :--- | :--- | :--- |
| **Typical Latency Profile** | 15 to 40+ microseconds | **Sub-microsecond** to low single-digit microseconds |
| **Memory Management** | Heavy JVM Heap allocations (Strings, Objects per field) | **100% Off-heap** via Agrona `DirectBuffer` |
| **Hot-Path Garbage Collection** | High object churn causing frequent GC pauses | **Zero-allocation** on the hot-path (Zero GC overhead) |
| **Threading Model** | Multi-threaded with coarse locks and thread sleeps | **Single-threaded/Lock-free** session actors |
| **Inter-Process Transport** | Standard Java I/O / Sockets | **Aeron IPC/UDP** ring buffers |
| **Persistence / Logging** | JDBC/File-based text logs | **Aeron Archive** sequential binary logging |

### Architectural Explanations
* **QuickFIX/J Performance Limits**: QuickFIX/J treats every inbound FIX tag as a new object instance (e.g., `new String()`, `new BigDecimal()`). At high throughput, this induces aggressive memory churn. Even when paired with concurrent garbage collectors like ZGC, the CPU cycles required to parse text and manage heap references introduce microsecond jitter at the 99.9th percentile. 
* **Artio Performance Profiles**: Artio bypasses the JVM heap entirely during execution. When a packet hits the network interface card (NIC), Artio streams the raw bytes directly into an Agrona off-heap ring buffer. The fields are parsed using flyweight patterns—pointers that read directly out of raw memory blocks rather than copying data into Java object instances. 

---

## 3. Artio Engine-to-Library Baseline Architecture

In a low-latency Artio architecture, the system is split into two processes to isolate network processing from your core trading logic: the **Engine** and the **Library**. They communicate over **Aeron IPC** (using shared memory ring buffers), meaning that handing off a message from the network engine to your application takes **nanoseconds**, without touching the JVM heap.

```
[ External FIX Client/Broker ]
            │
            ▼ (TCP/IP)
┌────────────────────────────────────────────────────────┐
│                     ARTIO ENGINE                       │
│  • Manages TCP connections & session state machine     │
│  • Manages inbound/outbound sequence numbers           │
├────────────────────────────────────────────────────────┤
│     Inbound Fix Parser       │   Outbound Encoder      │
└───────────┬────────────────────────────▲───────────────┘
            │ (Agrona Off-Heap)          │ (Agrona Off-Heap)
            ▼                            │
┌────────────────────────────────────────────────────────┐
│                 AERON MEDIA DRIVER                     │
│  • Manages shared memory ring buffers / IPC channels   │
└───────────┬────────────────────────────▲───────────────┘
            │                            │
            │ Aeron IPC Channel          │ Aeron IPC Channel
            ▼                            │
┌────────────────────────────────────────────────────────┐
│                     ARTIO LIBRARY                      │
│  • Runs inside your Trading Application process        │
│  • Zero-copy callbacks via Agrona DirectBuffers        │
├────────────────────────────────────────────────────────┤
│              YOUR CORE TRADING LOGIC                   │
└────────────────────────────────────────────────────────┘
```

### Core Components
* **Artio Engine**: Acts as the perimeter guardian. It handles the raw TCP connection, maintains the FIX session state (logons, heartbeats), and validates sequence numbers using lock-free event loops (`AgentRunner`).
* **Aeron Media Driver**: The high-performance transport manager. It coordinates the off-heap memory-mapped files where data streams are staged.
* **Artio Library**: A client library embedded directly into your core trading application. The callback passes an Agrona `DirectBuffer` containing pointers to the raw memory, meaning your application inspects fields without copying them onto the JVM heap.

---

## 4. Integrating Artio with 60East AMPS

**Yes, it is entirely possible** to have Artio publish FIX messages to a **60East AMPS** (Advanced Message Processing System) messaging server. Because they belong to two different software ecosystems, you must implement a bridging layer inside your **Artio Library (Application) process**.

### The High-Performance Bridging Path

To maintain ultra-low latency, the bridging path should run completely off-heap and zero-allocation up until the data hits the AMPS client buffer.

```
                  ┌──────────────────────┐
                  │     ARTIO ENGINE     │
                  └──────────┬───────────┘
                             │
                             │ (Aeron IPC - Off-Heap)
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │            ARTIO LIBRARY (YOUR APPLICATION)            │
 │                                                        │
 │  1. Callback receives Agrona DirectBuffer (Raw FIX)   │
 │  2. Inspect fields (Flyweight pattern / Zero-Heap)   │
 │  3. Construct AMPS Command using memory pointers       │
 └───────────────────────────┬────────────────────────────┘
                             │
                             │ (AMPS Java Client API)
                             ▼
                  ┌──────────────────────┐
                  │    60EAST AMPS 60    │
                  └──────────────────────┘
```

### Architectural Best Practices for this Deployment

* **Avoid JVM Heap Conversions**: When Artio provides the raw FIX stream bytes in an Agrona `DirectBuffer`, do not convert them into a `java.lang.String`. Use the **AMPS Java Client API** commands that accept byte buffers or raw pointer references to stream data directly into the AMPS outbound buffer.
* **Match Serialization Types**: Ensure your AMPS server topic type is configured to handle the exact serialization schema (`fix` or `binary`) you choose to forward.
* **Maintain the Single-Threaded Event Loop**: Artio’s library callbacks execute sequentially on a dedicated agent thread. Verify that your AMPS publisher instance uses non-blocking socket I/O or queues the outbound command quickly so it never blocks the Aeron ring buffer.
* **Leverage AMPS HA Bookmarks**: If you require guaranteed delivery, utilize AMPS Guaranteed Publishing by assigning unique sequence numbers to the `publish` commands sent to AMPS, aligning perfectly with Artio's sequence numbers.