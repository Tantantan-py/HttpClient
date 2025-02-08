import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import com.google.gson.Gson;


public class HttpClientTestPost {

    private static String url = "http://localhost:8080/multi_threads_war_exploded/";

    final static private int NUM_THREADS = 32;
    final static private int NUM_INITIAL_REQUESTS = 1000;
    final static private int TOTAL_REQUESTS = 200_000;


    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) throws IOException, InterruptedException {

        Thread[] threads = new Thread[NUM_THREADS];

        for (int i = 0; i < NUM_INITIAL_REQUESTS; i++) {
            // FIXME: At startup, you must create 32 threads that each send 1000 POST requests and terminate. Once any of these have completed you are free to create as few or as many threads as you like until all the 200K POSTS have been sent.

            Runnable thread = () -> {
                try {
                    // FIXME: generate random skier lift ride event json is right, but should separate from another single thread
                    String skierRide = SkierRide.GenerateRandomSkierRide().toString();
                    Gson gson = new Gson();
                    String json = gson.toJson(skierRide);

                    // add json header
                    // TODO: a posting thread never has to wait for an event to be available.
                    // TODO: it consumes as little CPU and memory as possible, making maximum capacity available for making POST requests
                    HttpRequest request = HttpRequest.newBuilder()
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .uri(URI.create(url))
                            .header("Content-Type", "application/json")
                            .build();

                    // send post request
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                    // print response
                    // TODO: The server will return an HTTP 201 response code for a successful POST operation. As soon as the 201 is received, the client thread should immediately send the next request until it has exhausted the number of requests to send.
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