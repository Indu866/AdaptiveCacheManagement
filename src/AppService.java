package src;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.concurrent.Executors;

public class AppService {
    private static final String CENTRAL_CACHE_URL = System.getenv().getOrDefault("CACHE_HOST", "http://central-cache:9090");

    public static void main(String[] args) throws Exception {
        int port = 8081;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));

        server.createContext("/data", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String key = "key1";
            if (query != null && query.contains("key=")) {
                key = query.split("key=")[1].split("&")[0];
            }

            // Call Central Cache Node
            boolean isHit = false;
            try {
                URL url = new URL(CENTRAL_CACHE_URL + "/get?key=" + key);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(1000);

                if (conn.getResponseCode() == 200) {
                    InputStream is = conn.getInputStream();
                    String response = new String(is.readAllBytes());
                    if (response.contains("\"status\":\"HIT\"")) {
                        isHit = true;
                    }
                }
            } catch (Exception ignored) {}

            // On Cache Miss, simulate Database fetch with 20ms latency
            if (!isHit) {
                try { Thread.sleep(20); } catch (InterruptedException ignored) {}
            }

            String resp = String.format("{\"key\":\"%s\",\"cacheHit\":%b,\"servedBy\":\"%s\"}", 
                                         key, isHit, System.getenv().getOrDefault("HOSTNAME", "instance-1"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp.getBytes());
            }
        });

        server.start();
        System.out.println("App Service Instance active on port " + port);
    }
}