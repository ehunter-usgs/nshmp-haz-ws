package gov.usgs.earthquake.nshm.www;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static gov.usgs.earthquake.nshm.www.ServletUtil.GSON;
import static gov.usgs.earthquake.nshm.www.ServletUtil.MODEL_CACHE_CONTEXT_ID;
import static gov.usgs.earthquake.nshm.www.Util.readDoubleValue;
import static gov.usgs.earthquake.nshm.www.Util.readValue;
import static gov.usgs.earthquake.nshm.www.Util.Key.DISTANCE;
import static gov.usgs.earthquake.nshm.www.Util.Key.EDITION;
import static gov.usgs.earthquake.nshm.www.Util.Key.LATITUDE;
import static gov.usgs.earthquake.nshm.www.Util.Key.LONGITUDE;
import static gov.usgs.earthquake.nshm.www.Util.Key.REGION;
import static gov.usgs.earthquake.nshm.www.Util.Key.TIMESPAN;

import static org.opensha2.calc.CurveValue.ANNUAL_RATE;
import static org.opensha2.calc.CurveValue.POISSON_PROBABILITY;

import org.opensha2.calc.CalcConfig;
import org.opensha2.calc.CalcConfig.Builder;
import org.opensha2.calc.CurveValue;
import org.opensha2.calc.EqRate;
import org.opensha2.calc.Site;
import org.opensha2.data.XySequence;
import org.opensha2.eq.model.HazardModel;
import org.opensha2.eq.model.SourceType;
import org.opensha2.geo.Location;
import org.opensha2.internal.Parsing;
import org.opensha2.internal.Parsing.Delimiter;

import com.google.common.base.Optional;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import gov.usgs.earthquake.nshm.www.HazardService.Curve;
import gov.usgs.earthquake.nshm.www.meta.Edition;
import gov.usgs.earthquake.nshm.www.meta.Metadata;
import gov.usgs.earthquake.nshm.www.meta.Region;
import gov.usgs.earthquake.nshm.www.meta.Status;

/**
 * Earthquake probability and rate calculation service.
 *
 * @author Peter Powers
 */
@SuppressWarnings("unused")
@WebServlet(
    name = "Earthquake Probability & Rate Service",
    description = "USGS NSHMP Earthquake Probability & Rate Calculator",
    urlPatterns = {
        "/rate",
        "/rate/*",
        "/probability",
        "/probability/*" })
public final class RateService extends HttpServlet {

  /*
   * Developer notes:
   *
   * The RateService is currently single-threaded and does not submit jobs to a
   * request queue; see HazardService. However, jobs are placed on a thread in
   * the CALC_EXECUTOR thread pool to handle parallel calculation of CEUS and
   * WUS models.
   */

  @Override
  protected void doGet(
      HttpServletRequest request,
      HttpServletResponse response)
      throws ServletException, IOException {

    response.setContentType("application/json; charset=UTF-8");

    String query = request.getQueryString();
    String pathInfo = request.getPathInfo();
    String host = request.getServerName();
    String service = request.getServletPath();

    CurveValue format = service.equals("/rate") ? ANNUAL_RATE : POISSON_PROBABILITY;
    String usage = (format == ANNUAL_RATE) ? Metadata.RATE_USAGE : Metadata.PROBABILITY_USAGE;
    int paramCount = (format == ANNUAL_RATE) ? 5 : 6;

    /*
     * Checking custom header for a forwarded protocol so generated links can
     * use the same protocol and not cause mixed content errors.
     */
    String protocol = request.getHeader("X-FORWARDED-PROTO");
    if (protocol == null) {
      /* Not a forwarded request. Honor reported protocol and port. */
      protocol = request.getScheme();
      host += ":" + request.getServerPort();
    }

    if (isNullOrEmpty(query) && isNullOrEmpty(pathInfo)) {
      response.getWriter().printf(usage, protocol, host);
      return;
    }

    StringBuffer urlBuf = request.getRequestURL();
    if (query != null) urlBuf.append('?').append(query);
    String url = urlBuf.toString();

    url = url.replace("http://", protocol + "://");

    RequestData requestData;

    try {
      if (query != null) {
        /* process query '?' request */
        requestData = buildRequest(request.getParameterMap(), format);
      } else {
        /* process slash-delimited request */
        List<String> params = Parsing.splitToList(pathInfo, Delimiter.SLASH);
        if (params.size() < paramCount) {
          response.getWriter().printf(usage, protocol, host);
          return;
        }
        requestData = buildRequest(params, format);
      }

      EqRate rates = calc(requestData, getServletContext());
      Result result = new Result.Builder()
          .requestData(requestData)
          .url(url)
          .rates(rates)
          .build();
      String resultStr = GSON.toJson(result);
      response.getWriter().print(resultStr);

    } catch (Exception e) {
      String message = Metadata.errorMessage(url, e, false);
      response.getWriter().print(message);
      getServletContext().log(url, e);
    }
  }

  /* Reduce query string key-value pairs */
  private RequestData buildRequest(Map<String, String[]> paramMap, CurveValue format) {

    Optional<Double> timespan = (format == POISSON_PROBABILITY)
        ? Optional.of(readDoubleValue(paramMap, TIMESPAN)) : Optional.<Double> absent();

    return new RequestData(
        readValue(paramMap, EDITION, Edition.class),
        readValue(paramMap, REGION, Region.class),
        readDoubleValue(paramMap, LONGITUDE),
        readDoubleValue(paramMap, LATITUDE),
        readDoubleValue(paramMap, DISTANCE),
        timespan);
  }

  /* Reduce slash-delimited request */
  private RequestData buildRequest(List<String> params, CurveValue format) {

    Optional<Double> timespan = (format == POISSON_PROBABILITY)
        ? Optional.of(Double.valueOf(params.get(5))) : Optional.<Double> absent();

    return new RequestData(
        readValue(params.get(0), Edition.class),
        readValue(params.get(1), Region.class),
        Double.valueOf(params.get(2)),
        Double.valueOf(params.get(3)),
        Double.valueOf(params.get(4)),
        timespan);
  }

  /*
   * TODO delete if not needed
   * 
   * Currently unused, however, will be used if it makes sense to submit jobs to
   * TASK_EXECUTOR.
   */
  private static class RateTask implements Callable<Result> {

    final String url;
    final RequestData data;
    final ServletContext context;

    RateTask(String url, RequestData data, ServletContext context) {
      this.url = url;
      this.data = data;
      this.context = context;
    }

    @Override
    public Result call() throws Exception {
      EqRate rates = calc(data, context);
      return new Result.Builder()
          .requestData(data)
          .url(url)
          .rates(rates)
          .build();
    }
  }

  private static EqRate calc(RequestData data, ServletContext context)
      throws InterruptedException, ExecutionException {

    Location location = Location.create(data.latitude, data.longitude);
    Site site = Site.builder().location(location).build();

    double distance = data.distance;

    @SuppressWarnings("unchecked")
    LoadingCache<Model, HazardModel> modelCache =
        (LoadingCache<Model, HazardModel>) context.getAttribute(MODEL_CACHE_CONTEXT_ID);

    EqRate rates;

    /*
     * Because we need to combine model results, intially calculate annual rates
     * and only then convert at the end if probability service has been called.
     */
    Optional<Double> emptyTimespan = Optional.<Double> absent();

    if (data.region == Region.COUS) {

      Model wusId = Model.valueOf(Region.WUS, data.edition.year());
      HazardModel wusModel = modelCache.get(wusId);
      ListenableFuture<EqRate> wusRates = process(wusModel, site, distance, emptyTimespan);

      Model ceusId = Model.valueOf(Region.CEUS, data.edition.year());
      HazardModel ceusModel = modelCache.get(ceusId);
      ListenableFuture<EqRate> ceusRates = process(ceusModel, site, distance, emptyTimespan);

      rates = EqRate.combine(wusRates.get(), ceusRates.get());

    } else {

      Model modelId = Model.valueOf(data.region, data.edition.year());
      HazardModel model = modelCache.get(modelId);
      rates = process(model, site, distance, emptyTimespan).get();
    }

    rates = EqRate.toCumulative(rates);
    if (data.timespan.isPresent()) {
      rates = EqRate.toPoissonProbability(rates, data.timespan.get());
    }
    return rates;
  }

  private static ListenableFuture<EqRate> process(
      HazardModel model,
      Site site,
      double distance,
      Optional<Double> timespan) {

    Builder configBuilder = CalcConfig.Builder
        .copyOf(model.config())
        .distance(distance);
    if (timespan.isPresent()) {
      /* Also sets value format to Poisson probability. */
      configBuilder.timespan(timespan.get());
    }
    CalcConfig config = configBuilder.build();
    Callable<EqRate> task = EqRate.callable(model, config, site);
    return ServletUtil.CALC_EXECUTOR.submit(task);
  }

  static final class RequestData {

    final Edition edition;
    final Region region;
    final double latitude;
    final double longitude;
    final double distance;
    final Optional<Double> timespan;

    RequestData(
        Edition edition,
        Region region,
        double longitude,
        double latitude,
        double distance,
        Optional<Double> timespan) {

      this.edition = edition;
      this.region = region;
      this.latitude = latitude;
      this.longitude = longitude;
      this.distance = distance;
      this.timespan = timespan;
    }
  }

  private static final class ResponseData {

    final Edition edition;
    final Region region;
    final double latitude;
    final double longitude;
    final double distance;
    final Double timespan;

    final String xlabel = "Magnitude (Mw)";
    final String ylabel;
    final List<Double> xvalues;

    ResponseData(RequestData request, List<Double> xvalues) {
      boolean isProbability = request.timespan.isPresent();
      this.edition = request.edition;
      this.region = request.region;
      this.longitude = request.longitude;
      this.latitude = request.latitude;
      this.distance = request.distance;
      this.ylabel = isProbability ? "Probability" : "Annual Rate (yr⁻¹)";
      this.timespan = request.timespan.orNull();
      this.xvalues = xvalues;
    }
  }

  private static final class Response {

    final ResponseData metadata;
    final List<Curve> data;

    Response(ResponseData metadata, List<Curve> data) {
      this.metadata = metadata;
      this.data = data;
    }
  }

  private static final String TOTAL_KEY = "Total";

  private static final class Result {

    final String status = Status.SUCCESS.toString();
    final String date = ServletUtil.formatDate(new Date()); // TODO time
    final String url;
    final Object version = Metadata.VERSION;
    final Response response;

    Result(String url, Response response) {
      this.url = url;
      this.response = response;
    }

    static final class Builder {

      String url;
      RequestData request;
      EqRate rates;

      Builder rates(EqRate rates) {
        checkState(this.rates == null, "Rate data has already been added to this builder");
        this.rates = rates;
        return this;
      }

      Builder url(String url) {
        this.url = url;
        return this;
      }

      Builder requestData(RequestData request) {
        this.request = request;
        return this;
      }

      Result build() {

        ImmutableList.Builder<Curve> curveListBuilder = ImmutableList.builder();

        /* Total mfd. */
        Curve totalCurve = new Curve(
            TOTAL_KEY,
            rates.totalMfd.yValues());
        curveListBuilder.add(totalCurve);

        /* Source type mfds. */
        for (Entry<SourceType, XySequence> entry : rates.typeMfds.entrySet()) {
          Curve curve = new Curve(
              entry.getKey().toString(),
              entry.getValue().yValues());
          curveListBuilder.add(curve);
        }

        ResponseData responseData = new ResponseData(
            request,
            rates.totalMfd.xValues());
        Response response = new Response(responseData, curveListBuilder.build());
        return new Result(url, response);
      }
    }
  }
}
