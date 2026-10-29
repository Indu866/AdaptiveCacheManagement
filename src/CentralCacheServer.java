package src;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.OutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

public class CentralCacheServer {
    public static final CacheEngine.CacheManager cacheManager = new CacheEngine.CacheManager(100);
    private static final CopyOnWriteArrayList<OutputStream> sseClients = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws IOException {
        int port = 9090;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));

        // GET Request from App Services
        server.createContext("/get", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String key = "default";
            if (query != null && query.contains("key=")) {
                key = query.split("key=")[1].split("&")[0];
            }

            String value = cacheManager.request(key);
            String response = (value != null) ? "{\"status\":\"HIT\",\"value\":\"" + value + "\"}" : "{\"status\":\"MISS\"}";

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        // Phase control endpoint
        server.createContext("/set-phase", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("phase=")) {
                cacheManager.setPhase(query.split("phase=")[1]);
            }
            String resp = "{\"status\":\"OK\"}";
            exchange.sendResponseHeaders(200, resp.length());
            exchange.getResponseBody().write(resp.getBytes());
            exchange.getResponseBody().close();
        });

        // SSE Real-Time Metrics Stream for Dashboard
        server.createContext("/metrics-stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, 0);

            OutputStream os = exchange.getResponseBody();
            sseClients.add(os);
        });

        // Static Dashboard HTML Handler
        server.createContext("/", exchange -> {
            File file = new File("public/index.html");
            if (!file.exists()) {
                file = new File("/app/public/index.html");
            }
            if (file.exists()) {
                exchange.getResponseHeaders().set("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, file.length());
                try (InputStream is = new FileInputStream(file); OutputStream os = exchange.getResponseBody()) {
                    is.transferTo(os);
                }
            } else {
                String error = "Dashboard HTML not found";
                exchange.sendResponseHeaders(404, error.length());
                exchange.getResponseBody().write(error.getBytes());
                exchange.getResponseBody().close();
            }
        });

        server.start();
        System.out.println("Central Cache Engine running on port " + port);

        // Broadcast metrics to dashboard every 100ms
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            String metricsJson = cacheManager.getMetricsJson();
            String sseData = "data: " + metricsJson + "\n\n";
            byte[] bytes = sseData.getBytes(StandardCharsets.UTF_8);

            for (OutputStream os : sseClients) {
                try {
                    os.write(bytes);
                    os.flush();
                } catch (IOException e) {
                    sseClients.remove(os);
                }
            }
        }, 0, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
}