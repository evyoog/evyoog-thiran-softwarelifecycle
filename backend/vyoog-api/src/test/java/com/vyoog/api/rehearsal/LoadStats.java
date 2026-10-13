package com.vyoog.api.rehearsal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** VYB-0959: latencies per named request, and the percentiles reported from them. */
public final class LoadStats {

    public record Row(String name, int count, int errors, long p50, long p95, long p99, long max) {}

    private final Map<String, ConcurrentLinkedQueue<Long>> latencies = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> errors = new ConcurrentHashMap<>();
    private final Map<String, String> firstError = new ConcurrentHashMap<>();

    public void record(String name, long nanos, boolean ok, String detail) {
        latencies.computeIfAbsent(name, k -> new ConcurrentLinkedQueue<>()).add(nanos / 1_000_000);
        if (!ok) {
            errors.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
            firstError.putIfAbsent(name, detail);
        }
    }

    public List<Row> rows() {
        List<Row> out = new ArrayList<>();
        for (var e : new TreeMap<>(latencies).entrySet()) {
            long[] sorted = e.getValue().stream().mapToLong(Long::longValue).sorted().toArray();
            out.add(new Row(e.getKey(), sorted.length, errors.getOrDefault(e.getKey(), new AtomicInteger()).get(),
                pct(sorted, 50), pct(sorted, 95), pct(sorted, 99), sorted[sorted.length - 1]));
        }
        return out;
    }

    public int total() { return latencies.values().stream().mapToInt(q -> q.size()).sum(); }

    public int totalErrors() { return errors.values().stream().mapToInt(AtomicInteger::get).sum(); }

    public Map<String, String> firstErrors() { return firstError; }

    /** All reads together (GET), or all writes together (POST and PATCH): the two figures the targets are set on. */
    public long percentile(boolean writes, int p) {
        long[] sorted = latencies.entrySet().stream().filter(e -> e.getKey().startsWith("GET") != writes)
            .flatMap(e -> e.getValue().stream()).mapToLong(Long::longValue).sorted().toArray();
        return pct(sorted, p);
    }

    private static long pct(long[] sorted, int p) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.min(sorted.length - 1, Math.ceil(p / 100.0 * sorted.length) - 1);
        return sorted[Math.max(0, idx)];
    }

    @Override
    public String toString() { return Arrays.toString(rows().toArray()); }
}
