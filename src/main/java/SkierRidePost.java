import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.http.HttpServletResponse;

public class SkierRidePost {
    private static final String SERVER_URL = "http://34.221.190.17:8080/multi-threads_war/skiers/12/seasons/2019/day/1/skier/123";
//    private static final String SERVER_URL = "http://localhost:8080/multi_threads_war_exploded/skiers/12/seasons/2019/day/1/skier/123";


    private static final int INITIAL_THREADS = 32;
    private static final int INITIAL_REQUESTS_PER_THREAD = 1_000;
    private static final int TOTAL_REQUESTS = 200_000;
    private static final int MAXIMUM_THREAD_POOL_SIZE = 512;

    private static LinkedBlockingQueue<String> requestQueue = new LinkedBlockingQueue<>();
    private static final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
    private static AtomicInteger completedRequests = new AtomicInteger(0);
    private static AtomicInteger unsuccessfulRequests = new AtomicInteger(0);
    private static ThreadPoolExecutor executor;
    private static final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(10)).build();

    public static void main(String[] args) throws IOException, InterruptedException {
        long startTime = System.currentTimeMillis();

        // Generate all 200K requests
        Thread generatorThread = new Thread(new SkierRideGenerator());
        generatorThread.start();

        /* For debugging:
        // Create a scheduler to run a task every minute
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        final AtomicInteger lastCount = new AtomicInteger(0);

        scheduler.scheduleAtFixedRate(() -> {
            int currentCount = completedRequests.get();
            int requestsLastMinute = currentCount - lastCount.getAndSet(currentCount);
            System.out.println("Requests handled in the last 10 seconds: " + requestsLastMinute);
        }, 1, 10, TimeUnit.SECONDS);

         */

        // Initialize thread pool
        executor = new ThreadPoolExecutor(INITIAL_THREADS, MAXIMUM_THREAD_POOL_SIZE, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        ExecutorCompletionService<Void> completionService = new ExecutorCompletionService<>(executor);

        // Submit first 32 tasks
        for (int i = 0; i < INITIAL_THREADS; i++) {
            completionService.submit(new PostTask(INITIAL_REQUESTS_PER_THREAD), null);
        }

        // Continue submitting new tasks until all 200K requests are processed
        while (completedRequests.get() < TOTAL_REQUESTS) {
            // Debugging info
            //System.out.println("Current completed requests: " + completedRequests.get() + "\n Active threads: " + executor.getActiveCount() + "\n Queue size: " + executor.getQueue().size() + "\n Pool size: " + executor.getPoolSize() + "\n Maximum pool size set at: " + executor.getMaximumPoolSize() +"\n Consumed time in ms: " + (System.currentTimeMillis() - startTime));

            // Wait for a thread to finish, then submit another batch
            completionService.take();  // Blocks until one task is done

            int remaining = TOTAL_REQUESTS - completedRequests.get();
            if (remaining <= 0) {
                break;
            }

            // Adjust the batch size if fewer requests remain
            int batchSize = Math.min(INITIAL_REQUESTS_PER_THREAD, remaining);
            completionService.submit(new PostTask(batchSize), null);
        }

        // Shutdown the executor
        executor.shutdown();
        executor.awaitTermination(60, TimeUnit.SECONDS);

        long endTime = System.currentTimeMillis();
        double runTimeSeconds = (endTime - startTime) / 1000.0;

        if (latencies.isEmpty()) {
            System.out.println("No latencies recorded.");
            return;
        }
        computeAndDisplayStatistics(latencies, TOTAL_REQUESTS, runTimeSeconds);

        // Final statistics
        System.out.println("Number of successful requests sent: " + completedRequests.get());
        System.out.println("Number of unsuccessful requests: " + unsuccessfulRequests.get());
        System.out.println("Total run time (ms): " + (endTime - startTime));
        System.out.println("Total throughput (requests per second): " + (TOTAL_REQUESTS / runTimeSeconds));
        System.out.println("Test Done!");
    }

    static class SkierRideGenerator implements Runnable {
        @Override
        public void run() {
            try {
                for (int i = 0; i < TOTAL_REQUESTS; i++) {
                    SkierRide ride = SkierRide.GenerateRandomSkierRide();
                    String requestJson = new Gson().toJson(ride);
                    requestQueue.put(requestJson);
                }
                System.out.println("All lift ride generated!");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static class PostTask implements Runnable {
        private final int requestNum;
        private static final String CSV_FILE = "request_logs.csv";
        private static final Object fileLock = new Object(); // Ensure thread-safe writes

        public PostTask(int requestNum) {
            this.requestNum = requestNum;
        }

        @Override
        public void run() {
            int sent = 0;
            while (sent < requestNum) {
                try {
                    String reqJson = requestQueue.poll(1, TimeUnit.SECONDS);
                    if (reqJson == null) break; // Exit if no requests left

                    long startTime = System.currentTimeMillis();
                    int responseCode = postCheck(reqJson);
                    long endTime = System.currentTimeMillis();
                    long latency = endTime - startTime;
                    latencies.add(latency);

                    logToCSV(startTime, "POST", latency, responseCode);

                    if (responseCode == HttpServletResponse.SC_CREATED) {
                        completedRequests.incrementAndGet();
                    } else {
                        unsuccessfulRequests.incrementAndGet();
                    }
                    sent++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        private int postCheck(String reqJson) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .POST(HttpRequest.BodyPublishers.ofString(reqJson))
                        .uri(URI.create(SERVER_URL))
                        .header("Content-Type", "application/json")
                        .build();

                HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                return res.statusCode();
            } catch (Exception e) {
                e.printStackTrace();
                return -1;
            }
        }

        private void logToCSV(long startTime, String requestType, long latency, int responseCode) {
            synchronized (fileLock) {
                try (FileWriter fw = new FileWriter(CSV_FILE, true);
                     BufferedWriter bw = new BufferedWriter(fw);
                     PrintWriter out = new PrintWriter(bw)) {
                    if (new File(CSV_FILE).length() == 0) {
                        out.println("startTime,requestType,latency,responseCode");
                    }
                    out.println(startTime + "," + requestType + "," + latency + "," + responseCode);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static void computeAndDisplayStatistics(List<Long> latencies, int totalRequests, double wallTimeSeconds) {
        Collections.sort(latencies);

        double mean = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        long median = latencies.get(latencies.size() / 2);
        long p99 = latencies.get((int) Math.ceil(latencies.size() * 0.99) - 1);
        long min = latencies.get(0);
        long max = latencies.get(latencies.size() - 1);
        double throughput = totalRequests / wallTimeSeconds;

        System.out.println("\n--- Performance Metrics ---");
        System.out.println("Mean response time (ms): " + mean);
        System.out.println("Median response time (ms): " + median);
        System.out.println("99th percentile response time (ms): " + p99);
        System.out.println("Min response time (ms): " + min);
        System.out.println("Max response time (ms): " + max);
        System.out.println("Throughput (requests/sec): " + throughput);
        System.out.println("---------------------------\n");
    }
}

/*
Client 1 record:

1 thread, 1 initial request per thread configured to handle 1 single request:
70 ms latency

1 thread to handle 1_000 requests with 1_000 initial requests per thread:
Total time takes about: 500 ms
through put estimated: 2_000 requests per second

32 threads to handle 10_000 requests with 1_000 initial requests per thread:
total time takes about: 2_000 ms
through put estimated: 5_000 requests per second

32 threads to handle 32_000 requests with 1_000 initial requests per thread:
total time takes about: 3_000 ms
through put estimated: 10_000 requests per second

32 threads to handle 200_000 requests with 1_000 initial requests per thread:
total time takes about: 10_000 ms
through put estimated: 20_000 requests per second
 */
