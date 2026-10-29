package src;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;

public class WorkloadSimulator {
    private static final String APP_SERVICE_URL = System.getenv().getOrDefault("APP_HOST", "http://app-service:8081");
    private static final String CACHE_CONTROL_URL = System.getenv().getOrDefault("CACHE_HOST", "http://central-cache:9090");
    private static final Random random = new Random(42);

    public static void main(String[] args) throws Exception {
        System.out.println("Workload Generator starting in 5 seconds...");
        Thread.sleep(5000);

        while (true) {
            // Phase 1: Zipfian (1000 requests - Steady State)
            setPhase("Zipfian");
            System.out.println("Starting Phase 1: Zipfian Traffic...");
            for (int i = 0; i < 1000; i++) {
                int key = getZipfKey(500, 0.8);
                sendRequest("zipf_key_" + key);
                Thread.sleep(10);
            }

            // Phase 2: Burst Flash Crowd (750 requests - 50 loops of 15 new keys)
            // 15 keys > Static Window (1), but <= Max Adaptive Window (20)
            setPhase("Burst");
            System.out.println("Starting Phase 2: Burst Flash Crowd (15 New Keys)...");
            for (int r = 0; r < 50; r++) {
                for (int b = 0; b < 15; b++) {
                    sendRequest("burst_key_" + b);
                    Thread.sleep(8);
                }
            }

            // Phase 3: Scan Phase (500 requests - Interleaved Zipfian + Single-Use Scan Keys)
            setPhase("Scan");
            System.out.println("Starting Phase 3: Interleaved Scan Sweep...");
            for (int s = 0; s < 500; s++) {
                if (s % 2 == 0) {
                    int key = getZipfKey(500, 0.8);
                    sendRequest("zipf_key_" + key);
                } else {
                    sendRequest("scan_key_" + (5000 + s));
                }
                Thread.sleep(10);
            }

            System.out.println("Simulation cycle complete. Resetting in 3 seconds...");
            Thread.sleep(3000);
        }
    }

    private static void sendRequest(String key) {
        try {
            URL url = new URL(APP_SERVICE_URL + "/data?key=" + key);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception ignored) {}
    }

    private static void setPhase(String phase) {
        try {
            URL url = new URL(CACHE_CONTROL_URL + "/set-phase?phase=" + phase);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception ignored) {}
    }

    private static int getZipfKey(int numKeys, double skew) {
        double bottom = 0;
        for (int i = 1; i <= numKeys; i++) bottom += (1 / Math.pow(i, skew));
        double dice = random.nextDouble();
        double sum = 0;
        for (int j = 1; j <= numKeys; j++) {
            sum += (1 / Math.pow(j, skew)) / bottom;
            if (sum >= dice) return j;
        }
        return 1;
    }
}