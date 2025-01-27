import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HttpClientTestPost {

    private static String url = "http://localhost:8080/multi_threads_war_exploded/";

    final static private int NUM_THREADS = 32;
    final static private int NUM_START_REQUESTS = 1000;
    final static private int TOTAL_REQUESTS = 200_000;


    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) throws IOException, InterruptedException {

        Thread[] threads = new Thread[NUM_THREADS];

        for (int i = 0; i < TOTAL_REQUESTS; i++) {

            Runnable thread = () -> {
                try {
                    // FIXME: generate random skier lift ride event json is right, but should separate from another single thread
                    String json = SkierRide.GenerateRandomSkierRide().toString();

                    // add json header
                    HttpRequest request = HttpRequest.newBuilder()
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .uri(URI.create(url))
                            .header("Content-Type", "application/json")
                            .build();

                    // send post request
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                    // print response
                    System.out.println(response.statusCode());
                    System.out.println(response.body());


                } catch (IOException | InterruptedException e) {
                    System.err.println("Error in thread " + Thread.currentThread().getId() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            };

            threads[i] = new Thread(thread);
            threads[i].start();
        }

    }
}