package com.newrelic.contributors.service;

import com.jayway.jsonpath.JsonPath;
import com.newrelic.contributors.domain.Top;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.vavr.CheckedFunction0;
import io.vavr.control.Try;
import org.slf4j.Logger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheLoaderException;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.ServiceUnavailableException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.InputStream;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Singleton
class GitHubCacheLoader implements CacheLoader<String, List> {

    @Inject
    private Logger logger;

    @Inject
    private Properties configuration;

    private RetryConfig retryConfig;

    private Client client = ClientBuilder.newClient();

    private final JsonPath path = JsonPath.compile("$.items[*]");

    @Override
    public List load(final String key) throws CacheLoaderException {

        logger.info("Loading contributors for key {}", key);

        final Retry retry = Retry.of("GitHub - User Search", retryConfig);
        retry.getEventPublisher().onEvent(e -> logger.debug(e.toString()));

        final CheckedFunction0<List> retriedResults =
                Retry.decorateCheckedSupplier(retry,
                        () -> retrieveTopContributors(key, Top.MAX_VALUE, 1)
                                .getOrElseThrow(Function.identity()));

        return Try.of(retriedResults)
                .onFailure(t -> logger.warn(t.getMessage()))
                .getOrElseThrow(t -> t instanceof RuntimeException ?
                        (RuntimeException) t : new ServiceUnavailableException(t.getMessage()));
    }

    @Override
    public Map<String, List> loadAll(final Iterable<? extends String> keys)
            throws CacheLoaderException {

        return StreamSupport.stream(keys.spliterator(), false)
                .collect(Collectors.toMap(Function.identity(), this::load));
    }

    @SuppressWarnings("unchecked")
    private Try<List> retrieveTopContributors(String location, int top, int page) {

        final Try<Response> response = request(location, top, page);
        final Try<InputStream> body = response.flatMapTry(this::read);

        return body.mapTry(b -> (List) path.read(b))
                .onSuccess(r -> {
                    if (top == 150) { // GitHub max is 100
                        retrieveTopContributors(location, 50, 3)
                                .onSuccess(r::addAll);
                    }
                });
    }

    private Try<Response> request(final String location, final int perPage, int page) {
        return Try.of(() -> client.target("https://api.github.com/search/users")
                .queryParam("q", "location:" + location)
                .queryParam("sort", "repositories")
                .queryParam("per_page", perPage)
                .queryParam("page", page)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .get());
    }

    private Try<InputStream> read(final Response response) {
        switch (Response.Status.fromStatusCode(response.getStatus())) {
            case OK:
                return Try.of(() -> response.readEntity(InputStream.class));
            case UNAUTHORIZED:
                return Try.failure(new NotAuthorizedException("Bad credentials"));
            case FORBIDDEN:
                long retryAfter = getRetryAfter(response);
                return Try.failure(new ServiceUnavailableException("Rate limit exceeded", retryAfter));
            default:
                return Try.failure(new ServiceUnavailableException("Could not connect GitHub"));
        }
    }

    private long getRetryAfter(final Response response) {
        Object first = response.getHeaders().getFirst("X-RateLimit-Reset");
        long resetTime = Long.parseLong(Objects.toString(first));
        return resetTime - System.currentTimeMillis() / 1000;
    }

    @PostConstruct
    private void postConstruct() {
        int maxAttempts = Integer.parseInt(configuration.getProperty("retry.maxAttempts"));
        int waitDuration = Integer.parseInt(configuration.getProperty("retry.waitDuration.millis"));

        retryConfig = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.of(waitDuration, ChronoUnit.SECONDS))
                .build();
    }

    @PreDestroy
    private void preDestroy() {
        client.close();
    }

}
