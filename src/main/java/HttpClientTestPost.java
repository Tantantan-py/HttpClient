import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import com.google.gson.Gson;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.http.HttpServletResponse;


public class HttpClientTestPost {
    private static String SERVER_URL = "http://localhost:8080/multi_threads_war_exploded/";

    private static final int INITIAL_THREADS = 32;
    private static final int INITIAL_REQUESTS_PER_THREAD = 1000;
    private static final int TOTAL_REQUESTS = 200_000;
    private static final int MAXIMUM_THREAD_POOL_SIZE = 512;
    private static final int SCALE_CHECK_INTERVAL_MS = 5000;

    private static final int INCREASE_QUEUE_THRESHOLD = 10_000;
    private static final int DECREASE_QUEUE_THRESHOLD = 5_000;
    private static final double QUEUE_TO_THREAD_RATIO_THRESHOLD = 100.0;

    private static LinkedBlockingQueue<String> requestQueue = new LinkedBlockingQueue<>();
    private static AtomicInteger completedRequests = new AtomicInteger(0);
    private static ThreadPoolExecutor executor;

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) throws IOException, InterruptedException {
        // Create a new thread for handling the random skier ride generator
        new Thread(new SkierRideGenerator()).start();

        // Customize the thread pool for POST
        ThreadPoolExecutor executor = new ThreadPoolExecutor(INITIAL_THREADS,
            MAXIMUM_THREAD_POOL_SIZE, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), new CallerRunsPolicy());

        for (int i = 0; i < INITIAL_THREADS; i++) {
            executor.execute(new PostTask(INITIAL_REQUESTS_PER_THREAD));
        }

        new Thread(() -> scaleThreadPool()).start();

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

                // Scale up if the queue size exceeds threshold and based on thread-to-queue ratio
                if (requestQueueSize > INCREASE_QUEUE_THRESHOLD || queueToThreadRatio > QUEUE_TO_THREAD_RATIO_THRESHOLD) {
                    int targetThreads;

                    if (activeThreads <= INITIAL_THREADS) {
                        targetThreads = 128; // Scale from 32 → 128
                    } else if (activeThreads <= 128) {
                        targetThreads = 256; // Scale from 128 → 256
                    } else {
                        targetThreads = MAXIMUM_THREAD_POOL_SIZE; // Final scale from 256 → 512
                    }

                    executor.setCorePoolSize(targetThreads);
                    executor.setMaximumPoolSize(targetThreads);
                    System.out.println("Increased thread pool size to: " + targetThreads);
                }
                // Scale down
                else if (requestQueueSize < DECREASE_QUEUE_THRESHOLD && queueToThreadRatio < QUEUE_TO_THREAD_RATIO_THRESHOLD) {
                    int targetThreads;

                    if (activeThreads >= 512) {
                        targetThreads = 256;
                    } else if (activeThreads >= 256) {
                        targetThreads = 128;
                    } else {
                        targetThreads = INITIAL_THREADS;
                    }

                    executor.setCorePoolSize(targetThreads);
                    executor.setMaximumPoolSize(targetThreads);
                    System.out.println("Decreased thread pool size to: " + targetThreads);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        executor.shutdown();
    }
}