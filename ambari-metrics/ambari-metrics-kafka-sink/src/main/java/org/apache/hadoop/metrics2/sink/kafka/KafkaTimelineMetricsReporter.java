/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.metrics2.sink.kafka;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import kafka.metrics.KafkaMetricsConfig;
import kafka.metrics.KafkaMetricsReporter;
import kafka.utils.VerifiableProperties;

import org.apache.commons.lang.ClassUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.metrics2.sink.timeline.AbstractTimelineMetricsSink;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetric;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetrics;
import org.apache.hadoop.metrics2.sink.timeline.cache.TimelineMetricsCache;
import org.apache.hadoop.metrics2.util.Servers;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.Histogram;
import com.yammer.metrics.core.Metered;
import com.yammer.metrics.core.Metric;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.MetricProcessor;
import com.yammer.metrics.core.MetricsRegistry;
import com.yammer.metrics.core.Summarizable;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.stats.Snapshot;

public class KafkaTimelineMetricsReporter extends AbstractTimelineMetricsSink implements KafkaMetricsReporter,
    KafkaTimelineMetricsReporterMBean {

  private final static Log LOG = LogFactory.getLog(KafkaTimelineMetricsReporter.class);

  private static final String TIMELINE_METRICS_SEND_INTERVAL_PROPERTY = "kafka.timeline.metrics.sendInterval";
  private static final String TIMELINE_METRICS_MAX_ROW_CACHE_SIZE_PROPERTY = "kafka.timeline.metrics.maxRowCacheSize";
  private static final String TIMELINE_HOST_PROPERTY = "kafka.timeline.metrics.host";
  private static final String TIMELINE_PORT_PROPERTY = "kafka.timeline.metrics.port";
  private static final String TIMELINE_REPORTER_ENABLED_PROPERTY = "kafka.timeline.metrics.reporter.enabled";
  private static final String TIMELINE_DEFAULT_HOST = "localhost";
  private static final String TIMELINE_DEFAULT_PORT = "8188";

  private boolean initialized = false;
  private boolean running = false;
  private final Object lock = new Object();
  private String collectorUri;
  private String hostname;
  private SocketAddress socketAddress;
  private TimelineScheduledReporter reporter;
  private TimelineMetricsCache metricsCache;

  @Override
  protected SocketAddress getServerSocketAddress() {
    return socketAddress;
  }

  @Override
  protected String getCollectorUri() {
    return collectorUri;
  }

  public void setMetricsCache(TimelineMetricsCache metricsCache) {
    this.metricsCache = metricsCache;
  }

  public void init(VerifiableProperties props) {
    synchronized (lock) {
      if (!initialized) {
        LOG.info("Initializing Kafka Timeline Metrics Sink");
        try {
          hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
          LOG.error("Could not identify hostname.");
          throw new RuntimeException("Could not identify hostname.", e);
        }
        KafkaMetricsConfig metricsConfig = new KafkaMetricsConfig(props);
        int metricsSendInterval = Integer.parseInt(props.getString(TIMELINE_METRICS_SEND_INTERVAL_PROPERTY,
            String.valueOf(TimelineMetricsCache.MAX_EVICTION_TIME_MILLIS)));
        int maxRowCacheSize = Integer.parseInt(props.getString(TIMELINE_METRICS_MAX_ROW_CACHE_SIZE_PROPERTY,
            String.valueOf(TimelineMetricsCache.MAX_RECS_PER_NAME_DEFAULT)));
        String metricCollectorHost = props.getString(TIMELINE_HOST_PROPERTY, TIMELINE_DEFAULT_HOST);
        String metricCollectorPort = props.getString(TIMELINE_PORT_PROPERTY, TIMELINE_DEFAULT_PORT);
        setMetricsCache(new TimelineMetricsCache(maxRowCacheSize, metricsSendInterval));
        collectorUri = "http://" + metricCollectorHost + ":" + metricCollectorPort + "/ws/v1/timeline/metrics";
        List<InetSocketAddress> socketAddresses = Servers.parse(metricCollectorHost,
            Integer.parseInt(metricCollectorPort));
        if (socketAddresses != null && !socketAddresses.isEmpty()) {
          socketAddress = socketAddresses.get(0);
        }
        initializeReporter();
        if (props.getBoolean(TIMELINE_REPORTER_ENABLED_PROPERTY, false)) {
          startReporter(metricsConfig.pollingIntervalSecs());
        }
        if (LOG.isTraceEnabled()) {
          LOG.trace("CollectorUri = " + collectorUri);
          LOG.trace("SocketAddress = " + socketAddress);
          LOG.trace("MetricsSendInterval = " + metricsSendInterval);
          LOG.trace("MaxRowCacheSize = " + maxRowCacheSize);
        }
      }
    }
  }

  public String getMBeanName() {
    return "kafka:type=org.apache.hadoop.metrics2.sink.kafka.KafkaTimelineMetricsReporter";
  }

  public synchronized void startReporter(long period) {
    synchronized (lock) {
      if (initialized && !running) {
        reporter.start(period, TimeUnit.SECONDS);
        running = true;
        LOG.info(String.format("Started Kafka Timeline metrics reporter with polling period %d seconds", period));
      }
    }
  }

  public synchronized void stopReporter() {
    synchronized (lock) {
      if (initialized && running) {
        reporter.stop();
        running = false;
        LOG.info("Stopped Kafka Timeline metrics reporter");
        initializeReporter();
      }
    }
  }

  private void initializeReporter() {
    reporter = new TimelineScheduledReporter(Metrics.defaultRegistry(), "timeline-scheduled-reporter",
        TimeUnit.SECONDS, TimeUnit.MILLISECONDS);
    initialized = true;
  }

  interface Context {
    public List<TimelineMetric> getTimelineMetricList();
  }

  class TimelineScheduledReporter extends ScheduledReporter implements MetricProcessor<Context> {

    private static final String APP_ID = "kafka_broker";
    private static final String COUNT_SUFIX = ".count";
    private static final String ONE_MINUTE_RATE_SUFIX = ".1MinuteRate";
    private static final String MEAN_SUFIX = ".mean";
    private static final String MEAN_RATE_SUFIX = ".meanRate";
    private static final String FIVE_MINUTE_RATE_SUFIX = ".5MinuteRate";
    private static final String FIFTEEN_MINUTE_RATE_SUFIX = ".15MinuteRate";
    private static final String MIN_SUFIX = ".min";
    private static final String MAX_SUFIX = ".max";
    private static final String MEDIAN_SUFIX = ".median";
    private static final String STD_DEV_SUFIX = "stddev";
    private static final String SEVENTY_FIFTH_PERCENTILE_SUFIX = ".75percentile";
    private static final String NINETY_FIFTH_PERCENTILE_SUFIX = ".95percentile";
    private static final String NINETY_EIGHTH_PERCENTILE_SUFIX = ".98percentile";
    private static final String NINETY_NINTH_PERCENTILE_SUFIX = ".99percentile";
    private static final String NINETY_NINE_POINT_NINE_PERCENTILE_SUFIX = ".999percentile";

    protected TimelineScheduledReporter(MetricsRegistry registry, String name, TimeUnit rateUnit, TimeUnit durationUnit) {
      super(registry, name, rateUnit, durationUnit);
    }

    @Override
    public void report(Set<Entry<MetricName, Metric>> metrics) {
      final List<TimelineMetric> metricsList = new ArrayList<TimelineMetric>();
      try {
        for (Entry<MetricName, Metric> entry : metrics) {
          final MetricName metricName = entry.getKey();
          final Metric metric = entry.getValue();
          Context context = new Context() {

            public List<TimelineMetric> getTimelineMetricList() {
              return metricsList;
            }

          };
          metric.processWith(this, metricName, context);
        }
      } catch (Throwable t) {
        LOG.error("Exception processing Kafka metric", t);
      }
      if (LOG.isTraceEnabled()) {
        LOG.trace("Metrics List size: " + metricsList.size());
        LOG.trace("Metics Set size: " + metrics.size());
      }
      if (!metricsList.isEmpty()) {
        TimelineMetrics timelineMetrics = new TimelineMetrics();
        timelineMetrics.setMetrics(metricsList);
        try {
          emitMetrics(timelineMetrics);
        } catch (IOException e) {
          LOG.error("Unexpected error", e);
        } catch (Throwable t) {
          LOG.error("Exception emitting metrics", t);
        }
      }
    }

    private TimelineMetric createTimelineMetric(long currentTimeMillis, String component, String attributeName,
        Number attributeValue) {
      if (LOG.isTraceEnabled()) {
        LOG.trace("Creating timeline metric: " + attributeName + " = " + attributeValue + " time = "
            + currentTimeMillis + " app_id = " + component);
      }
      TimelineMetric timelineMetric = new TimelineMetric();
      timelineMetric.setMetricName(attributeName);
      timelineMetric.setHostName(hostname);
      timelineMetric.setAppId(component);
      timelineMetric.setStartTime(currentTimeMillis);
      timelineMetric.setType(ClassUtils.getShortCanonicalName(attributeValue, "Number"));
      timelineMetric.getMetricValues().put(currentTimeMillis, attributeValue.doubleValue());
      return timelineMetric;
    }

    @Override
    public void processMeter(MetricName name, Metered meter, Context context) throws Exception {
      final long currentTimeMillis = System.currentTimeMillis();
      final String sanitizedName = sanitizeName(name);

      String[] metricNames = cacheKafkaMetered(currentTimeMillis, sanitizedName, meter);

      populateMetricsList(context, metricNames);
    }

    @Override
    public void processCounter(MetricName name, Counter counter, Context context) throws Exception {
      final long currentTimeMillis = System.currentTimeMillis();
      final String sanitizedName = sanitizeName(name);

      final String metricCountName = cacheSanitizedTimelineMetric(currentTimeMillis, sanitizedName,
          COUNT_SUFIX, counter.count());

      populateMetricsList(context, metricCountName);
    }

    @Override
    public void processHistogram(MetricName name, Histogram histogram, Context context) throws Exception {
      final long currentTimeMillis = System.currentTimeMillis();
      final Snapshot snapshot = histogram.getSnapshot();
      final String sanitizedName = sanitizeName(name);

      String[] metricHNames = cacheKafkaSummarizable(currentTimeMillis, sanitizedName, histogram);
      String[] metricSNames = cacheKafkaSnapshot(currentTimeMillis, sanitizedName, snapshot);

      String[] metricNames = new String[] {
          metricHNames[0],
          metricHNames[1],
          metricSNames[0],
          metricHNames[2],
          metricSNames[1],
          metricSNames[2],
          metricSNames[3],
          metricSNames[4],
          metricSNames[5],
          metricHNames[3] };
      populateMetricsList(context, metricNames);
    }

    @Override
    public void processTimer(MetricName name, Timer timer, Context context) throws Exception {
      final long currentTimeMillis = System.currentTimeMillis();
      final Snapshot snapshot = timer.getSnapshot();
      final String sanitizedName = sanitizeName(name);

      String[] metricMNames = cacheKafkaMetered(currentTimeMillis, sanitizedName, timer);
      String[] metricTNames = cacheKafkaSummarizable(currentTimeMillis, sanitizedName, timer);
      String[] metricSNames = cacheKafkaSnapshot(currentTimeMillis, sanitizedName, snapshot);

      String[] metricNames = new String[] {
          metricMNames[0],
          metricMNames[1],
          metricMNames[2],
          metricMNames[3],
          metricMNames[4],
          metricTNames[0],
          metricTNames[1],
          metricSNames[0],
          metricTNames[2],
          metricSNames[1],
          metricSNames[2],
          metricSNames[3],
          metricSNames[4],
          metricSNames[5],
          metricTNames[3] };
      populateMetricsList(context, metricNames);
    }

    @Override
    public void processGauge(MetricName name, Gauge<?> gauge, Context context) throws Exception {
      final long currentTimeMillis = System.currentTimeMillis();
      final String sanitizedName = sanitizeName(name);

      cacheSanitizedTimelineMetric(currentTimeMillis, sanitizedName, "", Double.parseDouble(String.valueOf(gauge.value())));

      populateMetricsList(context, sanitizedName);
    }

    private String[] cacheKafkaMetered(long currentTimeMillis, String sanitizedName, Metered meter) {
      final String meterCountName = cacheSanitizedTimelineMetric(currentTimeMillis, sanitizedName,
          COUNT_SUFIX, meter.count());
      final String meterOneMinuteRateName = cacheSanitizedTimelineMetric(currentTimeMillis, sanitizedName,
          ONE_MINUTE_RATE_SUFIX, meter.oneMinuteRate());
      final String meterMeanRateName = cacheSanitizedTimelineMetric(currentTimeMillis, sanitizedName,
          MEAN_RATE_SUFIX, meter.meanRate());
      final String meterFiveMinuteRateName = cacheSanitizedTimelineMetric(currentTimeMillis, sanitizedName,
          FIVE_MINUTE_RATE_SUFIX, meter.fiveMinuteRate());
      final String meterFifteenMinuteRateName = cacheSanitizedTimelineMetric(currentTimeMillis, sanitizedName,
          FIFTEEN_MINUTE_RATE_SUFIX, meter.fifteenMinuteRate());

      return new String[] { meterCountName, meterOneMinuteRateName, meterMeanRateName,
          meterFiveMinuteRateName, meterFifteenMinuteRateName };
    }

    private String[] cacheKafkaSummarizable(long currentTimeMillis, String sanitizedName, Summarizable summarizable) {
      final String minName = cacheSanitizedTimelineMetric(currentTimeMillis, sanitizedName,
          MIN_SUFIX, summarizable.min());
      final String maxName = cacheSanitizedTimelineMetric(currentTimeMillis, sanitizedName,
          MAX_SUFIX, summarizable.max());
      final String meanName = cacheSanitizedTimelineMetric(currentTimeMillis, sanitizedName,
          MEAN_SUFIX, summarizable.mean());
      final String stdDevName = cacheSanitizedTimelineMetric(currentTimeMillis, sanitizedName,
          STD_DEV_SUFIX, summarizable.stdDev());

      return new String[] { maxName, meanName, minName, stdDevName };
    }

    private String[] cacheKafkaSnapshot(long currentTimeMillis, String sanitizedName, Snapshot snapshot) {
      final String medianName = cacheSanitizedTimelineMetric(currentTimeMillis, sanitizedName,
          MEDIAN_SUFIX, snapshot.getMedian());
      final String seventyFifthPercentileName = cacheSanitizedTimelineMetric(currentTimeMillis, sanitizedName,
          SEVENTY_FIFTH_PERCENTILE_SUFIX, snapshot.get75thPercentile());
      final String ninetyFifthPercentileName = cacheSanitizedTimelineMetric(currentTimeMillis, sanitizedName,
          NINETY_FIFTH_PERCENTILE_SUFIX, snapshot.get95thPercentile());
      final String ninetyEighthPercentileName = cacheSanitizedTimelineMetric(currentTimeMillis, sanitizedName,
          NINETY_EIGHTH_PERCENTILE_SUFIX, snapshot.get98thPercentile());
      final String ninetyNinthPercentileName = cacheSanitizedTimelineMetric(currentTimeMillis, sanitizedName,
          NINETY_NINTH_PERCENTILE_SUFIX, snapshot.get99thPercentile());
      final String ninetyNinePointNinePercentileName = cacheSanitizedTimelineMetric(currentTimeMillis, sanitizedName,
          NINETY_NINE_POINT_NINE_PERCENTILE_SUFIX, snapshot.get999thPercentile());

      return new String[] { medianName,
          ninetyEighthPercentileName, ninetyFifthPercentileName, ninetyNinePointNinePercentileName,
          ninetyNinthPercentileName, seventyFifthPercentileName };
    }

    private String cacheSanitizedTimelineMetric(long currentTimeMillis, String sanitizedName, String suffix, Number metricValue) {
      final String meterName = sanitizedName + suffix;
      final TimelineMetric metric = createTimelineMetric(currentTimeMillis, APP_ID, meterName, metricValue);
      metricsCache.putTimelineMetric(metric);
      return meterName;
    }

    private void populateMetricsList(Context context, String... metricNames) {
      for (String metricName : metricNames) {
        TimelineMetric cachedMetric = metricsCache.getTimelineMetric(metricName);
        if (cachedMetric != null) {
          context.getTimelineMetricList().add(cachedMetric);
        }
      }
    }

    protected String sanitizeName(MetricName name) {
      if (name == null) {
        return "";
      }
      final String qualifiedTypeName = name.getGroup() + "." + name.getType() + "." + name.getName();
      final String metricName = name.hasScope() ? qualifiedTypeName + '.' + name.getScope() : qualifiedTypeName;
      final StringBuilder sb = new StringBuilder();
      for (int i = 0; i < metricName.length(); i++) {
        final char p = metricName.charAt(i);
        if (!(p >= 'A' && p <= 'Z') && !(p >= 'a' && p <= 'z') && !(p >= '0' && p <= '9') && (p != '_') && (p != '-')
            && (p != '.') && (p != '\0')) {
          sb.append('_');
        } else {
          sb.append(p);
        }
      }
      return sb.toString();
    }

  }
}
