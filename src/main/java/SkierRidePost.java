import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.google.gson.Gson;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.http.HttpServletResponse;

public class SkierRidePost {
    private static final String SERVER_URL = "http://localhost:8080/multi_threads_war_exploded/skiers/12/seasons/2019/day/1/skier/123";

    private static final int INITIAL_THREADS = 32;
    private static final int INITIAL_REQUESTS_PER_THREAD = 1000;
    private static final int TOTAL_REQUESTS = 200_000;
    private static final int MAXIMUM_THREAD_POOL_SIZE = 512;

    private static LinkedBlockingQueue<String> requestQueue = new LinkedBlockingQueue<>();
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
            System.out.println("Current completed requests: " + completedRequests.get() + "\n Current threads: " + executor.getActiveCount() + "\n Current queue size: " + executor.getQueue().size() + "\n Current pool size: " + executor.getPoolSize() + "\n Current time in ms: " + (System.currentTimeMillis() - startTime));
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

        // Final statistics
        System.out.println("Number of successful requests sent: " + completedRequests.get());
        System.out.println("Number of unsuccessful requests: " + unsuccessfulRequests.get());
        System.out.println("Total run time (ms): " + (endTime - startTime));
        System.out.println("Total throughput (requests per second): " + (TOTAL_REQUESTS / runTimeSeconds));
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

        public PostTask(int requestNum) {
            this.requestNum = requestNum;
        }

        @Override
        public void run() {
            int sent = 0;
            while (sent < requestNum) {
                try {
                    String reqJson = requestQueue.poll(2, TimeUnit.SECONDS);
                    if (reqJson == null) break; // Exit loop if queue is empty

                    if (postCheck(reqJson)) {
                        completedRequests.incrementAndGet();
                    } else {
                        unsuccessfulRequests.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                sent++;
            }
        }

        private boolean postCheck(String reqJson) {
            try {
                HttpRequest req = HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.ofString(reqJson)).uri(URI.create(SERVER_URL)).header("Content-Type", "application/json").build();

                HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                return res.statusCode() == HttpServletResponse.SC_CREATED;
            } catch (Exception e) {
                return false;
            }
        }
    }
}

/*
2070 ms single latency
 */
