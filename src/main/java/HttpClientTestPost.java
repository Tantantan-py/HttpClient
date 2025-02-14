import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.google.gson.Gson;

import java.util.concurrent.*;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.http.HttpServletResponse;


public class HttpClientTestPost {
    private static String SERVER_URL = "http://localhost:8080/multi_threads_war_exploded/skiers/12/seasons/2019/day/1/skier/123";

    private static final int INITIAL_THREADS = 32;
    private static final int INITIAL_REQUESTS_PER_THREAD = 1000;
    private static final int TOTAL_REQUESTS = 200_000;
    private static final int MAXIMUM_THREAD_POOL_SIZE = 512;
    private static final int SCALE_CHECK_INTERVAL_MS = 1000;

    private static final int INCREASE_QUEUE_THRESHOLD = 10_000;
    private static final int DECREASE_QUEUE_THRESHOLD = 5_000;
    private static final double QUEUE_TO_THREAD_RATIO_THRESHOLD = 100.0;

    private static LinkedBlockingQueue<String> requestQueue = new LinkedBlockingQueue<>();
    private static AtomicInteger completedRequests = new AtomicInteger(0);
    private static AtomicInteger unsuccessfulRequests = new AtomicInteger(0);
    private static ThreadPoolExecutor executor;

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) throws IOException, InterruptedException {
        // Start time
        long startTime = System.currentTimeMillis();

        // Create a new thread for the random skier ride generator
        Thread generatorThread = new Thread(new SkierRideGenerator());
        generatorThread.start();

        // The threads should take the requests from the requestQueue
        executor = new ThreadPoolExecutor(INITIAL_THREADS,
                MAXIMUM_THREAD_POOL_SIZE, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), new CallerRunsPolicy());

        for (int i = 0; i < INITIAL_THREADS; i++) {
            executor.execute(new PostTask(INITIAL_REQUESTS_PER_THREAD));
        }

        // TODO: Scale the thread pool based on the queue size
        // TODO: Once any of the initial 32 threads have completed then are free to create as few or as many threads as it like until all the 200K POSTS have been sent.
        Thread scaleThread = new Thread(HttpClientTestPost::scaleThreadPool);
        scaleThread.start();

        // Wait for all tasks to complete
        executor.shutdown();
        if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }

        long endTime = System.currentTimeMillis();
        long runTimeMillis = endTime - startTime;
        double runTimeSeconds = runTimeMillis / 1000.0;

        // Calculate throughput
        int success = completedRequests.get();
        int unsuccessful = unsuccessfulRequests.get();
        double throughput = TOTAL_REQUESTS / runTimeSeconds;

        // Final prints
        System.out.println("Number of successful requests sent: " + success);
        System.out.println("Number of unsuccessful requests: " + unsuccessful);
        System.out.println("Total run time (ms): " + runTimeMillis);
        System.out.println("Total throughput (requests per second): " + throughput);

    }


    static class SkierRideGenerator implements Runnable {
        @Override
        public void run() {
            for (int i = 0; i < TOTAL_REQUESTS; i++) {
                try {
                    SkierRide ride = SkierRide.GenerateRandomSkierRide();
                    Gson gson = new Gson();
                    String requestJson = gson.toJson(ride);

                    requestQueue.put(requestJson);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            System.out.println("All lift ride generated!");
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
                    /* Not sure if there would be a race condition to use below:
                    String reqJson = requestQueue.take();
                     */
                    String reqJson = requestQueue.poll(2, TimeUnit.SECONDS);
                    // If requestQueue is not empty, would not block
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
                HttpRequest req = HttpRequest.newBuilder()
                        .POST(HttpRequest.BodyPublishers.ofString(reqJson))
                        .uri(URI.create(SERVER_URL))
                        .header("Content-Type", "application/json")
                        .build();

                HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

                // For debugging
                // System.out.println("Status code: " + res.statusCode());

                return res.statusCode() == HttpServletResponse.SC_CREATED;
            } catch (Exception e) {
                return false;
            }
        }
    }

    private static void scaleThreadPool() {
        while (completedRequests.get() < TOTAL_REQUESTS) {
            try {
                Thread.sleep(SCALE_CHECK_INTERVAL_MS);

                int activeThreads = executor.getActiveCount();
                int requestQueueSize = requestQueue.size();
                double queueToThreadRatio = (activeThreads == 0) ? 0 : (double) requestQueueSize / activeThreads;

                int targetThreads = activeThreads;

                // Scale up: If the queue is large or thread-to-queue ratio is too high
                if (requestQueueSize > INCREASE_QUEUE_THRESHOLD || queueToThreadRatio > QUEUE_TO_THREAD_RATIO_THRESHOLD) {
                    if (activeThreads <= 32) {
                        targetThreads = 128;
                    } else if (activeThreads <= 128) {
                        targetThreads = 256;
                    } else {
                        targetThreads = MAXIMUM_THREAD_POOL_SIZE;
                    }
                }
                // Scale down: If queue is small and ratio is low
                else if (requestQueueSize < DECREASE_QUEUE_THRESHOLD && queueToThreadRatio < QUEUE_TO_THREAD_RATIO_THRESHOLD) {
                    if (activeThreads >= 512) {
                        targetThreads = 256;
                    } else if (activeThreads >= 256) {
                        targetThreads = 128;
                    } else {
                        targetThreads = INITIAL_THREADS;
                    }
                }

                // Apply new thread limits
                executor.setCorePoolSize(targetThreads);
                executor.setMaximumPoolSize(targetThreads);
                System.out.println("Adjusted thread pool size to: " + targetThreads);

                // **Ensure threads are actively handling requests**:
                int currentRunningThreads = executor.getActiveCount();
                while (currentRunningThreads < targetThreads && completedRequests.get() + unsuccessfulRequests.get() < TOTAL_REQUESTS) {
                    executor.execute(new PostTask(INITIAL_REQUESTS_PER_THREAD));
                    currentRunningThreads++;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        executor.shutdown();
    }
}