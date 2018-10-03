package com.linkedin.thirdeye.datasource.mock;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.linkedin.thirdeye.dataframe.DataFrame;
import com.linkedin.thirdeye.dataframe.StringSeries;
import com.linkedin.thirdeye.dataframe.util.DataFrameUtils;
import com.linkedin.thirdeye.datasource.ThirdEyeDataSource;
import com.linkedin.thirdeye.datasource.ThirdEyeRequest;
import com.linkedin.thirdeye.datasource.ThirdEyeResponse;
import com.linkedin.thirdeye.datasource.csv.CSVThirdEyeDataSource;
import com.linkedin.thirdeye.detection.ConfigUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Period;
import org.joda.time.PeriodType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.linkedin.thirdeye.dataframe.util.DataFrameUtils.*;


/**
 * MockThirdEyeDataSource generates time series based on generator configs. Once generated,
 * the data is cached in memory until the application terminates. This data source serves
 * testing and demo purposes.
 */
public class MockThirdEyeDataSource implements ThirdEyeDataSource {
  private static final Logger LOG = LoggerFactory.getLogger(MockThirdEyeDataSource.class);

  final Map<String, MockDataset> datasets;

  final Map<String, DataFrame> datasetData;
  final Map<Long, String> metricNameMap;

  final CSVThirdEyeDataSource delegate;

  /**
   * This constructor is invoked by Java Reflection to initialize a ThirdEyeDataSource.
   *
   * @param properties the properties to initialize this data source with
   * @throws Exception if properties cannot be parsed
   */
  public MockThirdEyeDataSource(Map<String, Object> properties) throws Exception {
    // datasets
    this.datasets = new HashMap<>();
    Map<String, Object> config = ConfigUtils.getMap(properties.get("datasets"));
    for (Map.Entry<String, Object> entry : config.entrySet()) {
      this.datasets.put(entry.getKey(), MockDataset.fromMap(
          entry.getKey(), ConfigUtils.<String, Object>getMap(entry.getValue())
      ));
    }

    LOG.info("Found {} datasets: {}", this.datasets.size(), this.datasets.keySet());

    // mock data
    final long tEnd = System.currentTimeMillis();
    final long tStart = tEnd - TimeUnit.DAYS.toMillis(28);

    LOG.info("Generating data for time range {} to {}", tStart, tEnd);

    // mock data per sub-dimension
    Map<Tuple, DataFrame> rawData = new HashMap<>();
    for (MockDataset dataset : this.datasets.values()) {
      for (String metric : dataset.metrics.keySet()) {
        String[] basePrefix = new String[] { dataset.name, "metrics", metric };

        Collection<Tuple> paths = makeTuples(dataset.metrics.get(metric), basePrefix, dataset.dimensions.size() + basePrefix.length);
        for (Tuple path : paths) {
          LOG.info("Generating '{}'", Arrays.asList(path.values));

          Map<String, Object> metricConfig = resolveTuple(config, path);
          rawData.put(path, makeData(metricConfig,
              new DateTime(tStart, dataset.timezone),
              new DateTime(tEnd, dataset.timezone),
              dataset.granularity));
        }
      }
    }

    // merge data
    long metricNameCounter = 0;
    this.datasetData = new HashMap<>();
    this.metricNameMap = new HashMap<>();

    // per dataset
    List<String> sortedDatasets = new ArrayList<>(this.datasets.keySet());
    Collections.sort(sortedDatasets);

    for (String datasetName : sortedDatasets) {
      MockDataset dataset = this.datasets.get(datasetName);

      Map<String, DataFrame> metricData = new HashMap<>();

      List<String> indexes = new ArrayList<>();
      indexes.add(COL_TIME);
      indexes.addAll(dataset.dimensions);

      // per metric
      List<String> sortedMetrics = new ArrayList<>(dataset.metrics.keySet());
      Collections.sort(sortedMetrics);

      for (String metric : sortedMetrics) {
        this.metricNameMap.put(1 + metricNameCounter++, metric);

        String[] prefix = new String[] { dataset.name, "metrics", metric };
        Collection<Tuple> tuples = filterTuples(rawData.keySet(), prefix);

        // per dimension
        List<DataFrame> dimensionData = new ArrayList<>();
        for (Tuple tuple : tuples) {
          String metricName = tuple.values[2]; // ["dataset", "metrics", "metric", ...]

          DataFrame dfExpanded = new DataFrame(rawData.get(tuple)).renameSeries(COL_VALUE, metricName);

          for (int i = 0; i < dataset.dimensions.size(); i++) {
            String dimValue = tuple.values[i + 3];
            String dimName = dataset.dimensions.get(i);
            dfExpanded.addSeries(dimName, StringSeries.fillValues(dfExpanded.size(), dimValue));
          }

          dfExpanded.setIndex(indexes);

          dimensionData.add(dfExpanded);
        }

        metricData.put(metric, DataFrame.concatenate(dimensionData));
      }

      List<String> fields = new ArrayList<>();
      fields.add(COL_TIME + ":LONG");
      for (String name : dataset.dimensions) {
        fields.add(name + ":STRING");
      }
      for (String name : dataset.metrics.keySet()) {
        fields.add(name + ":DOUBLE");
      }

      DataFrame dfDataset = DataFrame.builder(fields).build().setIndex(indexes);
      for (Map.Entry<String, DataFrame> entry : metricData.entrySet()) {
        String metricName = entry.getKey();
        dfDataset = dfDataset.joinOuter(entry.getValue())
            .renameSeries(metricName + "_right", metricName)
            .dropSeries(metricName + "_left");
      }

      this.datasetData.put(dataset.name, dfDataset);

      LOG.info("Merged '{}' with {} rows and {} columns", dataset.name, dfDataset.size(), dfDataset.getSeriesNames().size());
    }

    this.delegate = CSVThirdEyeDataSource.fromDataFrame(this.datasetData, this.metricNameMap);
  }

  @Override
  public String getName() {
    return MockThirdEyeDataSource.class.getSimpleName();
  }

  @Override
  public ThirdEyeResponse execute(ThirdEyeRequest request) throws Exception {
    return this.delegate.execute(request);
  }

  @Override
  public List<String> getDatasets() throws Exception {
    return new ArrayList<>(this.datasets.keySet());
  }

  @Override
  public void clear() throws Exception {
    // left blank
  }

  @Override
  public void close() throws Exception {
    // left blank
  }

  @Override
  public long getMaxDataTime(String dataset) throws Exception {
    return this.delegate.getMaxDataTime(dataset);
  }

  @Override
  public Map<String, List<String>> getDimensionFilters(String dataset) throws Exception {
    return this.delegate.getDimensionFilters(dataset);
  }

  /**
   * Returns a DataFrame populated with mock data for a given config and time range.
   *
   * @param config metric generator config
   * @param start start time
   * @param end end time
   * @param interval time granularity
   * @return DataFrame with mock data
   */
  private static DataFrame makeData(Map<String, Object> config, DateTime start, DateTime end, Period interval) {
    List<Long> timestamps = new ArrayList<>();
    List<Double> values = new ArrayList<>();

    double mean = MapUtils.getDoubleValue(config, "mean", 0);
    double std = MapUtils.getDoubleValue(config, "std", 1);
    NormalDistribution dist = new NormalDistribution(mean, std);

    DateTime origin = start.withFields(DataFrameUtils.makeOrigin(PeriodType.days()));
    while (origin.isBefore(end)) {
      if (origin.isBefore(start)) {
        origin = origin.plus(interval);
        continue;
      }

      timestamps.add(origin.getMillis());
      values.add((double) Math.max(Math.round(dist.sample()), 0));
      origin = origin.plus(interval);
    }

    return new DataFrame()
        .addSeries(COL_TIME, ArrayUtils.toPrimitive(timestamps.toArray(new Long[0])))
        .addSeries(COL_VALUE, ArrayUtils.toPrimitive(values.toArray(new Double[0])))
        .setIndex(COL_TIME);
  }

  /**
   * Returns list of tuples for (a metric's) nested generator configs.
   *
   * @param map nested config with generator configs
   * @param maxDepth max expected level of depth
   * @return metric tuples
   */
  private static List<Tuple> makeTuples(Map<String, Object> map, String[] basePrefix, int maxDepth) {
    List<Tuple> tuples = new ArrayList<>();

    LinkedList<MetricTuple> stack = new LinkedList<>();
    stack.push(new MetricTuple(basePrefix, map));

    while (!stack.isEmpty()) {
      MetricTuple tuple = stack.pop();
      if (tuple.prefix.length >= maxDepth) {
        tuples.add(new Tuple(tuple.prefix));

      } else {
        for (Map.Entry<String, Object> entry : tuple.map.entrySet()) {
          Map<String, Object> nested = (Map<String, Object>) entry.getValue();
          String[] prefix = Arrays.copyOf(tuple.prefix, tuple.prefix.length + 1);
          prefix[prefix.length - 1] = entry.getKey();

          stack.push(new MetricTuple(prefix, nested));
        }
      }
    }

    return tuples;
  }

  /**
   * Returns the bottom-level config for a given metric tuple from the root of a nested generator config
   *
   * @param map nested config with generator configs
   * @param path metric generator path
   * @return generator config
   */
  private static Map<String, Object> resolveTuple(Map<String, Object> map, Tuple path) {
    for (String element : path.values) {
      map = (Map<String, Object>) map.get(element);
    }
    return map;
  }

  /**
   * Returns a filtered collection of tuples for a given prefix
   *
   * @param tuples collections of tuples
   * @param prefix reuquired prefix
   * @return filtered collection of tuples
   */
  private static Collection<Tuple> filterTuples(Collection<Tuple> tuples, final String[] prefix) {
    return Collections2.filter(tuples, new Predicate<Tuple>() {
      @Override
      public boolean apply(@Nullable Tuple tuple) {
        if (tuple == null || tuple.values.length < prefix.length) {
          return false;
        }

        for (int i = 0; i < prefix.length; i++) {
          if (!StringUtils.equals(tuple.values[i], prefix[i])) {
            return false;
          }
        }

        return true;
      }
    });
  }

  /**
   * Container class for datasets and their generator configs
   */
  static final class MockDataset {
    final String name;
    final DateTimeZone timezone;
    final List<String> dimensions;
    final Map<String, Map<String, Object>> metrics;
    final Period granularity;

    MockDataset(String name, DateTimeZone timezone, List<String> dimensions, Map<String, Map<String, Object>> metrics, Period granularity) {
      this.name = name;
      this.timezone = timezone;
      this.dimensions = dimensions;
      this.metrics = metrics;
      this.granularity = granularity;
    }

    static MockDataset fromMap(String name, Map<String, Object> map) {
      return new MockDataset(
          name,
          DateTimeZone.forID(MapUtils.getString(map, "timezone", "America/Los_Angeles")),
          ConfigUtils.<String>getList(map.get("dimensions")),
          ConfigUtils.<String, Map<String, Object>>getMap(map.get("metrics")),
          ConfigUtils.parsePeriod(MapUtils.getString(map, "granularity", "1hour")));
    }
  }

  /**
   * Helper class for depth-first iteration of metric dimensions
   */
  static final class MetricTuple {
    final String[] prefix;
    final Map<String, Object> map;

    MetricTuple(String[] prefix, Map<String, Object> map) {
      this.prefix = prefix;
      this.map = map;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      MetricTuple that = (MetricTuple) o;
      return Arrays.equals(prefix, that.prefix) && Objects.equals(map, that.map);
    }

    @Override
    public int hashCode() {
      int result = Objects.hash(map);
      result = 31 * result + Arrays.hashCode(prefix);
      return result;
    }
  }

  /**
   * Helper class for comparable tuples
   */
  static final class Tuple {
    final String[] values;

    public Tuple(String[] values) {
      this.values = values;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Tuple tuple = (Tuple) o;
      return Arrays.equals(values, tuple.values);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(values);
    }
  }
}
