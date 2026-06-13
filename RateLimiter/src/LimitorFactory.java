import java.util.Collection;
import java.util.Collections;
import java.util.Map;

public class LimiterFactory {
    public Limiter create(Map<String,Object> config){
        String algorithm = (String) config.get("algorithm");
        Map<String, Object> algoConfig = (Map<String, Object>) config.get("algoConfig");

        if(algoConfig == null){
            algoConfig = Collections.emptyMap();
        }

        if(algorithm.equals("TokenBucket")){
            int capacity = ((Number) algoConfig.getOrDefault("capacity", 0)).intValue();
            int refillRate  = ((Number) algoConfig.getOrDefault("refillRatePerSecond", 0)).intValue();

            return new TokenBucketLimiter(capacity, refillRate);
        }

        if(algorithm.equals("SlidingWindowLog")){
            int maxRequests =  ((Number) algoConfig.getOrDefault("maxRequests", 0)).intValue();
            long windowMs = ((Number) algoConfig.getOrDefault("windowMs", 0)).longValue();
            return new SlidingWindowLogLimiter(maxRequests, windowMs);
        }

        throw new IllegalArgumentException("Unknown algorithm: " + algorithm);
    }
}
