package ctbrec.sites.stripchat;

import java.util.ArrayList;
import java.util.List;

public class StaticInfo {
    public static class HlsFallback {
        public boolean isEnabled;
        // public boolean isReportingEnabled;
        public int timeoutMS;
        public int healthyCheckInterval;
        public int unhealthyCheckInterval;
        public int maxFailsCount;
        public List<String> fallbackDomains = new ArrayList<>();
        public int lookupIntervalMinutes;
        public int numberOfConnectionErrors;
        public int numberOfConnectionSuccess;
        public int defaultDomainCheckInterval;
        public int defaultDomainHealthCheckSuccessCount;
    }
    
    public static class FeatureSettings {
        public HlsFallback hlsFallback;
    }
    
    public FeatureSettings featureSettings;
}
