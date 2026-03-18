# Binomial Heap — Java Implementation

A unified implementation of **four heap variants** in Java, with empirical performance benchmarks measuring links, cuts, heapify costs and per-operation cost across 3 experiments.

## Heap Variants

All four variants are implemented in a single `Heap` class, controlled by two boolean flags:

| Variant                  | `lazyMelds` | `lazyDecreaseKeys` |
|--------------------------|-------------|-------------------|
| Binomial Heap            | `false`     | `false`           |
| Lazy Binomial Heap       | `true`      | `false`           |
| Fibonacci Heap           | `true`      | `true`            |
| Binomial + Cuts          | `false`     | `true`            |

---

## Project Structure

```
binomial-heap-java/
├── src/
│   ├── Heap.java              # Core implementation — all 4 heap variants
│   ├── HeapExperiments.java   # Empirical benchmark: 3 experiments × 4 variants
│   ├── StudentTest.java       # Basic unit tests: insert, deleteMin, decreaseKey, delete
│   └── MegaHeapTest.java      # Extended stress tests
└── README.md
```

---

## How to Compile & Run

```bash
javac src/*.java -d out/
```

### Run unit tests
```bash
java -cp out StudentTest
```
```
Grade: 100 / 100
All tests passed!
```

### Run extended tests
```bash
java -cp out MegaHeapTest
```

### Run performance benchmarks
```bash
java -cp out HeapExperiments
# Optional: java -cp out HeapExperiments <n> <runs>
# Example:  java -cp out HeapExperiments 100000 5
```

---

## API Reference

```java
Heap heap = new Heap(lazyMelds, lazyDecreaseKeys);

Heap.HeapItem item = heap.insert(key, info);  // Insert key-value pair
Heap.HeapItem min  = heap.findMin();          // Return minimum node, O(1)
heap.deleteMin();                             // Remove minimum node
heap.delete(item);                            // Remove a specific node
heap.decreaseKey(item, delta);               // Decrease key by delta
int size  = heap.size();                      // Number of nodes
int trees = heap.numTrees();                  // Number of trees in heap
```

---

## Time Complexity

| Operation      | Binomial  | Lazy Binomial | Fibonacci | Binomial + Cuts |
|----------------|-----------|---------------|-----------|-----------------|
| `insert`       | O(log n)  | O(1)          | O(1)      | O(log n)        |
| `findMin`      | O(1)      | O(1)          | O(1)      | O(1)            |
| `deleteMin`    | O(log n)  | O(log n)*     | O(log n)* | O(log n)        |
| `decreaseKey`  | O(log n)  | O(log n)      | O(1)*     | O(1)*           |
| `delete`       | O(log n)  | O(log n)      | O(log n)* | O(log n)*       |

*amortized

---

## Benchmark Experiments

`HeapExperiments.java` runs 3 experiments on n = 464,646 elements, averaged over 20 random permutations:

**Experiment 1** — Insert n keys (random order), then `deleteMin` once.

**Experiment 2** — Insert n keys, `deleteMin` once, then delete the maximum key repeatedly until heap size reaches 46.

**Experiment 3** — Insert n keys, `deleteMin` once, then ⌊0.1n⌋ `decreaseKey` operations reducing selected keys to 0, then `deleteMin` once more.

**Metrics collected:**

| Metric         | Description                                    |
|----------------|------------------------------------------------|
| `avgTimeMs`    | Wall-clock time (milliseconds)                 |
| `avgFinalSize` | Heap size at end of experiment                 |
| `avgNumTrees`  | Number of trees in the heap forest             |
| `avgLinks`     | Total link operations performed                |
| `avgCuts`      | Total cut operations performed                 |
| `avgHeapifyUp` | Total heapify-up operations performed          |
| `avgMaxOpCost` | Max single-operation cost (Δlinks+Δcuts+Δheapify) |

---

## Tech Stack

- **Language:** Java
- **Testing:** Custom unit test runner (no external dependencies)
- **Build:** `javac` standard compiler
