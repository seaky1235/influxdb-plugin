package jenkinsci.plugins.influxdb.generators;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.influxdb.dto.Point;

import hudson.model.Run;
import hudson.util.IOUtils;
import jenkinsci.plugins.influxdb.renderer.MeasurementRenderer;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

public class SonarQubePointGenerator extends AbstractPointGenerator {
    
	public static final String BUILD_DISPLAY_NAME = "display_name";
	public static final String SONARQUBE_LINES_OF_CODE = "lines_of_code";
	public static final String SONARQUBE_COMPLEXITY = "complexity";
	public static final String SONARQUBE_CRTITCAL_ISSUES = "critical_issues";
	public static final String SONARQUBE_MAJOR_ISSUES = "major_issues";
	public static final String SONARQUBE_MINOR_ISSUES = "minor_issues";
	public static final String SONARQUBE_INFO_ISSUES = "info_issues";
	public static final String SONARQUBE_BLOCKER_ISSUES = "blocker_issues";

	public static final String URL_PATTERN_IN_LOGS = ".*" + Pattern.quote("ANALYSIS SUCCESSFUL, you can browse ")
			+ "(.*)";

	public static final String SONAR_ISSUES_BASE_URL = "/api/issues/search?ps=500&projectkeys=";

	public static final String SONAR_METRICS_BASE_URL = "/api/measures/component?metricKeys=ncloc,complexity,violations&componentKey=";

	private String SONAR_ISSUES_URL;
	private String SONAR_METRICS_URL;
	private String sonarServer;
	private String sonarProjectName;

	private final Run<?, ?> build;
	private final String customPrefix;

	public SonarQubePointGenerator(MeasurementRenderer<Run<?, ?>> measurementRenderer, String customPrefix,
			Run<?, ?> build) {
		super(measurementRenderer);
		this.build = build;
		this.customPrefix = customPrefix;
	}

	public boolean hasReport() {
		String sonarBuildLink = null;
		try {
			sonarBuildLink = getSonarProjectURLFromBuildLogs(build);
			if (!StringUtils.isEmpty(sonarBuildLink)) {
				setSonarDetails(sonarBuildLink);
				return true;
			} 
		} catch (IOException e) {
			//
		}
		
		return false;
	}

	public void setSonarDetails(String sonarBuildLink) {
		try {
			this.sonarProjectName = getSonarProjectName(sonarBuildLink);
			this.sonarServer = sonarBuildLink.substring(0,
					sonarBuildLink.indexOf("/dashboard/index/" + this.sonarProjectName));
			this.SONAR_ISSUES_URL = sonarServer + SONAR_ISSUES_BASE_URL + sonarProjectName + "&severities=";
			this.SONAR_METRICS_URL = sonarServer + SONAR_METRICS_BASE_URL + sonarProjectName;
		} catch (URISyntaxException e) {
			//
		}
		
	}
	public Point[] generate() {
		Point point = null;
		try {
			point = buildPoint(measurementName("sonarqube_data"), customPrefix, build)
					.addField(BUILD_DISPLAY_NAME, build.getDisplayName())
					.addField(SONARQUBE_CRTITCAL_ISSUES,
							getSonarIssues(this.SONAR_ISSUES_URL, "CRITICAL"))
					.addField(SONARQUBE_BLOCKER_ISSUES,
							getSonarIssues(this.SONAR_ISSUES_URL, "BLOCKER"))
					.addField(SONARQUBE_MAJOR_ISSUES,
							getSonarIssues(this.SONAR_ISSUES_URL, "MAJOR"))
					.addField(SONARQUBE_MINOR_ISSUES,
							getSonarIssues(this.SONAR_ISSUES_URL, "MINOR"))
					.addField(SONARQUBE_INFO_ISSUES,
							getSonarIssues(this.SONAR_ISSUES_URL, "INFO"))
					.addField(SONARQUBE_LINES_OF_CODE,
							getLinesofCode(this.SONAR_METRICS_URL))
					.build();
		} catch (IOException e) {
			//handle
		}
		return new Point[] { point };
	}


	public String getResult(String request) throws IOException {
		CloseableHttpClient client = null;
		CloseableHttpResponse response = null;
		StringBuffer result = new StringBuffer();
		try {

			client = HttpClientBuilder.create().build();
			HttpGet getrequest = new HttpGet(request);

			response = client.execute(getrequest);

			BufferedReader rd;

			rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));

			String line = "";
			while ((line = rd.readLine()) != null) {
				result.append(line);
			}

		} catch (UnsupportedOperationException | IOException e) {
			//handle
		} finally {
			if (response != null) {
				response.close();
			}
			if (client != null) {
				client.close();
			}
		}
		return result.toString();
	}

	@SuppressWarnings("deprecation")
	private String getSonarProjectURLFromBuildLogs(Run<?, ?> build) throws IOException {
		BufferedReader br = null;
		String url = null;
		try {
			br = new BufferedReader(build.getLogReader());
			String strLine;
			Pattern p = Pattern.compile(URL_PATTERN_IN_LOGS);
			while ((strLine = br.readLine()) != null) {
				Matcher match = p.matcher(strLine);
				if (match.matches()) {
					url = match.group(1);
				}
			}
		} finally {
			IOUtils.closeQuietly(br);
		}
		return url;
	}


	private String getSonarProjectName(String url) throws URISyntaxException {
		URI uri = new URI(url);
		String[] projectUrl = uri.getPath().split("/");
		if (projectUrl.length > 1) {
			return projectUrl[projectUrl.length - 1];
		} else
			return "";
	}

	public int getLinesofCode(String url) throws IOException {
		String output = getResult(url);
		JSONObject metricsObjects = JSONObject.fromObject(output);
		int linesofcodeCount = 0;
		JSONArray array = metricsObjects.getJSONObject("component").getJSONArray("measures");
		for (int i = 0; i < array.size(); i++) {
			JSONObject metricsObject = array.getJSONObject(i);
			if (metricsObject.get("metric").equals("ncloc")) {
				linesofcodeCount = metricsObject.getInt("value");
			}
		}
		
		return linesofcodeCount;
	}

	public int getSonarIssues(String url, String severity) throws IOException {
		String output = getResult(url+severity);
		return JSONObject.fromObject(output).getInt("total");
	}

}
