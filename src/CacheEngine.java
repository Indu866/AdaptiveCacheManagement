package src;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class CacheEngine {

    // --- 1. Thread-Safe Count-Min Sketch ---
    public static class CountMinSketch {
        private final int depth = 4;
        private final int width;
        private final int[][] table;
        private final int[] hashA;

        public CountMinSketch(int width) {
            this.width = width;
            this.table = new int[depth][width];
            this.hashA = new int[]{17, 31, 61, 127};
        }

        public synchronized void add(String key) {
            int hash = key.hashCode();
            for (int i = 0; i < depth; i++) {
                int idx = Math.abs((hash ^ hashA[i]) % width);
                if (table[i][idx] < Integer.MAX_VALUE) {
                    table[i][idx]++;
                }
            }
        }

        public synchronized int estimateCount(String key) {
            int hash = key.hashCode();
            int res = Integer.MAX_VALUE;
            for (int i = 0; i < depth; i++) {
                int idx = Math.abs((hash ^ hashA[i]) % width);
                res = Math.min(res, table[i][idx]);
            }
            return res == Integer.MAX_VALUE ? 0 : res;
        }
    }

    // --- 2. Thread-Safe A-W-TinyLFU ---
    public static class AdaptiveCache {
        private final int totalCapacity;
        private int windowCapacity;
        private final int minWindow;
        private final int maxWindow;
        private final int stepSize;

        private final LinkedHashMap<String, String> windowCache;
        private final LinkedHashMap<String, String> mainCache;

        private final Set<String> ghostWindow = Collections.synchronizedSet(new LinkedHashSet<>());
        private final Set<String> ghostMain = Collections.synchronizedSet(new LinkedHashSet<>());
        private final int maxGhostSize;

        private final CountMinSketch sketch;
        private final AtomicLong hits = new AtomicLong(0);
        private final AtomicLong misses = new AtomicLong(0);

        public AdaptiveCache(int capacity) {
            this.totalCapacity = capacity;
            this.minWindow = Math.max(1, (int) (capacity * 0.01));
            this.maxWindow = (int) (capacity * 0.20);
            this.windowCapacity = this.minWindow;
            this.stepSize = Math.max(1, capacity / 100);
            this.maxGhostSize = capacity;
            this.sketch = new CountMinSketch(1024);

            this.windowCache = new LinkedHashMap<>(16, 0.75f, true);
            this.mainCache = new LinkedHashMap<>(16, 0.75f, true);
        }

        public synchronized String get(String key) {
            sketch.add(key);

            // Use get() so LinkedHashMap updates access order to MRU position
            String val = windowCache.get(key);
            if (val == null) {
                val = mainCache.get(key);
            }

            if (val != null) {
                hits.incrementAndGet();
                return val;
            }

            misses.incrementAndGet();

            // Ghost queue adaptation triggers
            if (ghostWindow.contains(key)) {
                windowCapacity = Math.min(maxWindow, windowCapacity + stepSize);
                ghostWindow.remove(key);
            } else if (ghostMain.contains(key)) {
                windowCapacity = Math.max(minWindow, windowCapacity - stepSize);
                ghostMain.remove(key);
            }

            put(key, "val_" + key);
            return null;
        }

        private synchronized void put(String key, String value) {
            windowCache.put(key, value);

            if (windowCache.size() > windowCapacity) {
                Map.Entry<String, String> windowVictim = windowCache.entrySet().iterator().next();
                windowCache.remove(windowVictim.getKey());

                int candidateFreq = sketch.estimateCount(windowVictim.getKey());

                if (mainCache.size() < (totalCapacity - windowCapacity)) {
                    mainCache.put(windowVictim.getKey(), windowVictim.getValue());
                } else {
                    Map.Entry<String, String> mainVictim = mainCache.entrySet().iterator().next();
                    int victimFreq = sketch.estimateCount(mainVictim.getKey());

                    if (candidateFreq > victimFreq) {
                        mainCache.remove(mainVictim.getKey());
                        addToGhost(ghostMain, mainVictim.getKey());
                        mainCache.put(windowVictim.getKey(), windowVictim.getValue());
                    } else {
                        addToGhost(ghostWindow, windowVictim.getKey());
                    }
                }
            }
        }

        private void addToGhost(Set<String> ghost, String key) {
            synchronized (ghost) {
                if (ghost.size() >= maxGhostSize) {
                    Iterator<String> it = ghost.iterator();
                    if (it.hasNext()) { it.next(); it.remove(); }
                }
                ghost.add(key);
            }
        }

        public int getWindowCapacity() { return windowCapacity; }
        public long getHits() { return hits.get(); }
        public long getMisses() { return misses.get(); }
    }

    // --- 3. Thread-Safe Static W-TinyLFU (Fixed 1% Window) ---
    public static class StaticWTinyLFU {
        private final int totalCapacity;
        private final int windowCapacity;
        private final LinkedHashMap<String, String> windowCache;
        private final LinkedHashMap<String, String> mainCache;
        private final CountMinSketch sketch;
        private final AtomicLong hits = new AtomicLong(0);
        private final AtomicLong misses = new AtomicLong(0);

        public StaticWTinyLFU(int capacity) {
            this.totalCapacity = capacity;
            this.windowCapacity = Math.max(1, (int) (capacity * 0.01));
            this.sketch = new CountMinSketch(1024);
            this.windowCache = new LinkedHashMap<>(16, 0.75f, true);
            this.mainCache = new LinkedHashMap<>(16, 0.75f, true);
        }

        public synchronized void get(String key) {
            sketch.add(key);
            if (windowCache.get(key) != null || mainCache.get(key) != null) {
                hits.incrementAndGet();
                return;
            }
            misses.incrementAndGet();
            windowCache.put(key, "val_" + key);

            if (windowCache.size() > windowCapacity) {
                Map.Entry<String, String> windowVictim = windowCache.entrySet().iterator().next();
                windowCache.remove(windowVictim.getKey());

                if (mainCache.size() < (totalCapacity - windowCapacity)) {
                    mainCache.put(windowVictim.getKey(), windowVictim.getValue());
                } else {
                    Map.Entry<String, String> mainVictim = mainCache.entrySet().iterator().next();
                    if (sketch.estimateCount(windowVictim.getKey()) > sketch.estimateCount(mainVictim.getKey())) {
                        mainCache.remove(mainVictim.getKey());
                        mainCache.put(windowVictim.getKey(), windowVictim.getValue());
                    }
                }
            }
        }

        public long getHits() { return hits.get(); }
        public long getMisses() { return misses.get(); }
    }

    // --- 4. Thread-Safe Standard LRU ---
    public static class StandardLRU {
        private final int capacity;
        private final LinkedHashMap<String, String> cache;
        private final AtomicLong hits = new AtomicLong(0);
        private final AtomicLong misses = new AtomicLong(0);

        public StandardLRU(int capacity) {
            this.capacity = capacity;
            this.cache = new LinkedHashMap<>(capacity, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > capacity;
                }
            };
        }

        public synchronized void get(String key) {
            if (cache.get(key) != null) {
                hits.incrementAndGet();
            } else {
                misses.incrementAndGet();
                cache.put(key, "val_" + key);
            }
        }

        public long getHits() { return hits.get(); }
        public long getMisses() { return misses.get(); }
    }

    // --- 5. Central Cache Manager ---
    public static class CacheManager {
        public final AdaptiveCache adaptive;
        public final StaticWTinyLFU staticCache;
        public final StandardLRU lru;

        private final AtomicLong totalRequests = new AtomicLong(0);
        private String currentPhase = "Zipfian";

        private final LinkedList<Boolean> adaptiveHistory = new LinkedList<>();
        private final LinkedList<Boolean> staticHistory = new LinkedList<>();
        private final LinkedList<Boolean> lruHistory = new LinkedList<>();
        private final int slidingWindow = 100;

        public CacheManager(int capacity) {
            this.adaptive = new AdaptiveCache(capacity);
            this.staticCache = new StaticWTinyLFU(capacity);
            this.lru = new StandardLRU(capacity);
        }

        public synchronized String request(String key) {
            long reqId = totalRequests.incrementAndGet();

            long adaptiveHitsBefore = adaptive.getHits();
            String val = adaptive.get(key);
            boolean adaptiveHit = adaptive.getHits() > adaptiveHitsBefore;

            long staticHitsBefore = staticCache.getHits();
            staticCache.get(key);
            boolean staticHit = staticCache.getHits() > staticHitsBefore;

            long lruHitsBefore = lru.getHits();
            lru.get(key);
            boolean lruHit = lru.getHits() > lruHitsBefore;

            updateHistory(adaptiveHistory, adaptiveHit);
            updateHistory(staticHistory, staticHit);
            updateHistory(lruHistory, lruHit);

            return val;
        }

        private void updateHistory(LinkedList<Boolean> history, boolean hit) {
            history.addLast(hit);
            if (history.size() > slidingWindow) history.removeFirst();
        }

        private double getRollingHitRate(LinkedList<Boolean> history) {
            if (history.isEmpty()) return 0.0;
            int hits = 0;
            for (boolean b : history) if (b) hits++;
            return ((double) hits / history.size()) * 100.0;
        }

        public synchronized String getMetricsJson() {
            return String.format(Locale.US,
                "{\"step\":%d,\"phase\":\"%s\",\"adaptiveHitRate\":%.2f,\"staticHitRate\":%.2f,\"lruHitRate\":%.2f,\"windowCapacity\":%d}",
                totalRequests.get(), currentPhase,
                getRollingHitRate(adaptiveHistory),
                getRollingHitRate(staticHistory),
                getRollingHitRate(lruHistory),
                adaptive.getWindowCapacity()
            );
        }

        public void setPhase(String phase) { this.currentPhase = phase; }
        public long getTotalRequests() { return totalRequests.get(); }
    }
}