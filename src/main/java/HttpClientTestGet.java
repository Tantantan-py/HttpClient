import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HttpClientTestGet {

    private static String url = "http://localhost:8080/multi_threads_war_exploded/hello/12/seasons/2019/day/1/skier/123";

    final static private int NUM_THREADS = 100;

    public static void main(String[] args) throws IOException, InterruptedException {

        long startTime = System.currentTimeMillis();

        // Hold a threads array, so we can join them later to calculate total time
        Thread[] threads = new Thread[NUM_THREADS];

        for (int i = 0; i < NUM_THREADS; i++) {
            HttpClient httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_2)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            Runnable thread = () -> {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .build();

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                    System.out.println("For Thread " + Thread.currentThread().getId() + " - Status Code: " + response.statusCode());
                    System.out.println("Response Body: " + response.body());

                } catch (IOException | InterruptedException e) {
                    System.err.println("Error in thread " + Thread.currentThread().getId() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            };

            threads[i] = new Thread(thread);
            threads[i].start();
        }

        // Wait for all threads to finish before calculating total time
        for (int i = 0; i < NUM_THREADS; i++) {
            threads[i].join();  // Wait for the current thread to finish
        }

        long endTime = System.currentTimeMillis();

        long totalTime = endTime - startTime;
        System.out.println("Total time takes: " + totalTime + " ms");
    }
}
