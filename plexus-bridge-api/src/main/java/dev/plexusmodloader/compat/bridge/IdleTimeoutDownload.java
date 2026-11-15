package dev.plexusmodloader.compat.bridge;

import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link HttpClient#send} with an idle timeout on the response body. {@link HttpRequest#timeout()} ends once the
 * response headers arrive, so a transfer that stops afterwards would wait forever; this cancels an attempt after
 * {@code idle} without body data and tries again, up to {@code attempts} times.
 *
 * <p>Thread: any; blocks the calling thread like {@link HttpClient#send}.
 */
@ApiStability(StabilityLevel.INTERNAL)
public final class IdleTimeoutDownload {

    /** How long a response body may send no data before the attempt is given up. */
    public static final Duration IDLE = Duration.ofSeconds(60);

    /** How many times a request whose body stalls is sent. */
    public static final int ATTEMPTS = 3;

    // Sentinel: response headers not received yet, so only HttpRequest.timeout() applies.
    private static final long NOT_STARTED = Long.MIN_VALUE;

    private IdleTimeoutDownload() {}

    /** {@link #send(HttpClient, HttpRequest, HttpResponse.BodyHandler, Duration, int)} with {@link #IDLE} and {@link #ATTEMPTS}. */
    public static <T> HttpResponse<T> send(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return send(client, request, handler, IDLE, ATTEMPTS);
    }

    /**
     * Sends {@code request} and returns its response with any status code, as {@link HttpClient#send} does.
     *
     * @throws HttpTimeoutException when every attempt's body stalled for {@code idle}, or the headers did not arrive
     *     within the request's own timeout
     * @throws IOException when the request fails otherwise
     */
    public static <T> HttpResponse<T> send(
            HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler, Duration idle, int attempts)
            throws IOException, InterruptedException {
        if (attempts < 1 || idle.isNegative() || idle.isZero()) {
            throw new IllegalArgumentException("attempts " + attempts + ", idle " + idle);
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return once(client, request, handler, idle);
            } catch (BodyStalledException stalled) {
                if (attempt == attempts) {
                    throw new HttpTimeoutException(String.format(
                            Locale.ROOT,
                            "download from %s failed because the server stopped sending data for %s seconds across all %s attempts",
                            request.uri(),
                            idle.toSeconds(),
                            attempts));
                }
            }
        }
    }

    /**
     * A body handler that writes the response to {@code file}, emptying it first, so an attempt sent again after a stall
     * leaves nothing of the earlier one ({@link HttpResponse.BodyHandlers#ofFile(Path)} does not truncate).
     */
    public static HttpResponse.BodyHandler<Path> fileBody(Path file) {
        return HttpResponse.BodyHandlers.ofFile(
                file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static <T> HttpResponse<T> once(
            HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler, Duration idle)
            throws IOException, InterruptedException {
        AtomicLong lastData = new AtomicLong(NOT_STARTED);
        CompletableFuture<HttpResponse<T>> future = client.sendAsync(request, info -> {
            lastData.set(System.nanoTime());
            return new ProgressSubscriber<>(handler.apply(info), lastData);
        });
        long poll = Math.clamp(idle.toMillis() / 4, 10, 1000);
        try {
            while (true) {
                try {
                    return future.get(poll, TimeUnit.MILLISECONDS);
                } catch (TimeoutException waiting) {
                    long last = lastData.get();
                    if (last != NOT_STARTED && System.nanoTime() - last > idle.toNanos()) {
                        // Since JDK 16, cancelling sendAsync's future aborts the exchange and closes the body
                        // subscriber.
                        future.cancel(true);
                        throw new BodyStalledException();
                    }
                }
            }
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException(cause);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            throw interrupted;
        }
    }

    /** One attempt's body sent no data for the idle time. */
    private static final class BodyStalledException extends HttpTimeoutException {
        private static final long serialVersionUID = 1L;

        BodyStalledException() {
            super("body stalled");
        }
    }

    /** Records when body data last arrived, passing everything to {@code body}. */
    private record ProgressSubscriber<T>(HttpResponse.BodySubscriber<T> body, AtomicLong lastData)
            implements HttpResponse.BodySubscriber<T> {

        @Override
        public CompletionStage<T> getBody() {
            return body.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            body.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            lastData.set(System.nanoTime());
            body.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            body.onError(throwable);
        }

        @Override
        public void onComplete() {
            body.onComplete();
        }
    }
}
