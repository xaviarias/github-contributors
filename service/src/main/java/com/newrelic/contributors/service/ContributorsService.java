package com.newrelic.contributors.service;

import org.slf4j.Logger;

import javax.cache.Cache;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

@Singleton
public class ContributorsService {

    @Inject
    private Logger logger;

    @Inject
    private Cache<String, List> cache;

    @SuppressWarnings("unchecked")
    public List findTopContributors(final String location, final int top) {

        logger.info("Finding the {} top contributors in {}", top, location);

        final List result = cache.get(location);
        return result.subList(0, Math.min(result.size(), top));
    }

}
