/*
 * Copyright (c) 2014 T-Systems Multimedia Solutions GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.tsystems.mms.apm.performancesignature;

import de.tsystems.mms.apm.performancesignature.dynatrace.model.ChartDashlet;
import de.tsystems.mms.apm.performancesignature.dynatrace.model.DashboardReport;
import de.tsystems.mms.apm.performancesignature.dynatrace.model.Measure;
import de.tsystems.mms.apm.performancesignature.dynatrace.model.TestRun;
import de.tsystems.mms.apm.performancesignature.model.MeasureNameHelper;
import de.tsystems.mms.apm.performancesignature.util.PerfSigUtils;
import hudson.FilePath;
import hudson.Functions;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.ProminentProjectAction;
import hudson.model.Run;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultAction;
import hudson.tasks.test.TestResultProjectAction;
import hudson.util.*;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.apache.commons.lang.StringUtils;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.CategoryLabelPositions;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.category.StackedBarRenderer;
import org.jfree.data.category.CategoryDataset;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by rapi on 25.04.2014.
 */
public class PerfSigProjectAction implements ProminentProjectAction {
    private final AbstractProject<?, ?> project;

    public PerfSigProjectAction(final AbstractProject<?, ?> project) {
        this.project = project;
    }

    public String getIconFileName() {
        return "/plugin/" + Messages.PerfSigProjectAction_UrlName() + "/images/icon.png";
    }

    public String getDisplayName() {
        return Messages.PerfSigProjectAction_DisplayName();
    }

    public String getUrlName() {
        return Messages.PerfSigProjectAction_UrlName();
    }

    public AbstractProject<?, ?> getProject() {
        return this.project;
    }

    public TestResultProjectAction getTestResultProjectAction() {
        return project.getAction(TestResultProjectAction.class);
    }

    public PerfSigUtils getPerfSigUtils() {
        return new PerfSigUtils();
    }

    public void doSummarizerGraph(final StaplerRequest request, final StaplerResponse response) throws IOException, InterruptedException {
        if (ChartUtil.awtProblemCause != null) {
            // not available. send out error message
            response.sendRedirect2(request.getContextPath() + "/images/headless.png");
            return;
        }

        final String id = request.getParameter("id");
        final JSONArray jsonArray = JSONArray.fromObject(getDashboardConfiguration());

        if (request.getParameterMap().get("customName") == null && request.getParameterMap().get("customBuildCount") == null) {
            for (int i = 0; i < jsonArray.size(); i++) {
                final JSONObject obj = jsonArray.getJSONObject(i);
                if (obj.getString("id").equals(id)) {
                    ChartUtil.generateGraph(request, response, createChart(obj, buildDataSet(obj)), calcDefaultSize());
                    return;
                }
            }
        } else {
            for (DashboardReport dashboardReport : getLastDashboardReports())
                for (ChartDashlet chartDashlet : dashboardReport.getChartDashlets())
                    for (Measure measure : chartDashlet.getMeasures())
                        if (id.equals(DigestUtils.md5Hex(dashboardReport.getName() + chartDashlet.getName() + measure.getName()))) {
                            final JSONObject jsonObject = new JSONObject();
                            jsonObject.put("id", id);
                            jsonObject.put("dashboard", dashboardReport.getName());
                            jsonObject.put("chartDashlet", chartDashlet.getName());
                            jsonObject.put("measure", measure.getName());
                            jsonObject.put("customName", request.getParameter("customName"));
                            jsonObject.put("customBuildCount", request.getParameter("customBuildCount"));

                            ChartUtil.generateGraph(request, response, createChart(jsonObject, buildDataSet(jsonObject)), calcDefaultSize());
                            return;
                        }
        }
    }

    private CategoryDataset buildDataSet(final JSONObject jsonObject) throws IOException {
        final String dashboard = jsonObject.getString("dashboard");
        final String chartDashlet = jsonObject.getString("chartDashlet");
        final String measure = jsonObject.getString("measure");
        final String buildCount = jsonObject.getString("customBuildCount");
        int customBuildCount = 0, i = 0;
        if (StringUtils.isNotBlank(buildCount))
            customBuildCount = Integer.parseInt(buildCount);

        final List<DashboardReport> dashboardReports = getDashBoardReports(dashboard);
        final DataSetBuilder<String, ChartUtil.NumberOnlyBuildLabel> dsb = new DataSetBuilder<String, ChartUtil.NumberOnlyBuildLabel>();

        for (DashboardReport dashboardReport : dashboardReports) {
            double metricValue = 0;
            if (dashboardReport.getChartDashlets() != null) {
                Measure m = dashboardReport.getMeasure(chartDashlet, measure);
                if (m != null) metricValue = m.getMetricValue();
            }
            i++;
            dsb.add(metricValue, chartDashlet, new ChartUtil.NumberOnlyBuildLabel(dashboardReport.getBuild()));
            if (customBuildCount != 0 && i == customBuildCount) break;
        }
        return dsb.build();
    }

    private JFreeChart createChart(final JSONObject jsonObject, final CategoryDataset dataset) {
        final String measure = jsonObject.getString(Messages.PerfSigProjectAction_ReqParamMeasure());
        final String chartDashlet = jsonObject.getString("chartDashlet");
        final String testCase = jsonObject.getString("dashboard");
        final String customMeasureName = jsonObject.getString("customName");

        String unit = "", color = Messages.PerfSigProjectAction_DefaultColor();

        for (DashboardReport dr : getLastDashboardReports()) {
            if (dr.getName().equals(testCase)) {
                final Measure m = dr.getMeasure(chartDashlet, measure);
                if (m != null) {
                    unit = m.getUnit();
                    color = URLDecoder.decode(m.getColor());
                }
                break;
            }
        }

        String title = customMeasureName;
        if (StringUtils.isBlank(customMeasureName))
            title = PerfSigUtils.generateTitle(measure, chartDashlet);

        final JFreeChart chart = ChartFactory.createBarChart(title, // title
                "Build", // category axis label
                unit, // value axis label
                dataset, // data
                PlotOrientation.VERTICAL, // orientation
                false, // include legend
                false, // tooltips
                false // urls
        );

        chart.setBackgroundPaint(Color.white);

        final CategoryPlot plot = chart.getCategoryPlot();

        plot.setBackgroundPaint(Color.WHITE);
        plot.setOutlinePaint(null);
        plot.setForegroundAlpha(0.8f);
        plot.setRangeGridlinesVisible(true);
        plot.setRangeGridlinePaint(Color.black);

        final CategoryAxis domainAxis = new ShiftedCategoryAxis(null);
        plot.setDomainAxis(domainAxis);
        domainAxis.setCategoryLabelPositions(CategoryLabelPositions.UP_90);
        //domainAxis.setLowerMargin(0.0);
        //domainAxis.setUpperMargin(0.0);
        //domainAxis.setCategoryMargin(0.0);

        final NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setStandardTickUnits(NumberAxis.createIntegerTickUnits());

        final BarRenderer renderer = (BarRenderer) chart.getCategoryPlot().getRenderer();
        renderer.setSeriesPaint(0, Color.decode(color));

        return chart;
    }

    public void doTestRunGraph(final StaplerRequest request, final StaplerResponse response) throws IOException, InterruptedException {
        if (ChartUtil.awtProblemCause != null) {
            // not available. send out error message
            response.sendRedirect2(request.getContextPath() + "/images/headless.png");
            return;
        }

        if (request.getParameterMap().get("customName") == null && request.getParameterMap().get("customBuildCount") == null) {
            final JSONArray jsonArray = JSONArray.fromObject(getDashboardConfiguration());
            for (int i = 0; i < jsonArray.size(); i++) {
                final JSONObject obj = jsonArray.getJSONObject(i);
                if (obj.getString("id").equals("unittest_overview")) {
                    ChartUtil.generateGraph(request, response, createTestRunChart(buildTestRunDataSet(obj.getString("customBuildCount")),
                            obj.getString("customName")), calcDefaultSize());
                    return;
                }
            }
        } else {
            ChartUtil.generateGraph(request, response, createTestRunChart(buildTestRunDataSet(request.getParameter("customBuildCount")),
                    request.getParameter("customName")), calcDefaultSize());
        }
    }

    private CategoryDataset buildTestRunDataSet(final String customBuildCount) {
        final DataSetBuilder<String, ChartUtil.NumberOnlyBuildLabel> dsb = new DataSetBuilder<String, ChartUtil.NumberOnlyBuildLabel>();
        int buildCount = 0, i = 0;
        if (StringUtils.isNotBlank(customBuildCount))
            buildCount = Integer.parseInt(customBuildCount);

        for (AbstractBuild<?, ?> run : project.getBuilds()) {
            PerfSigTestDataWrapper testDataWrapper = run.getAction(PerfSigTestDataWrapper.class);
            if (testDataWrapper != null && testDataWrapper.getTestRuns() != null) {
                TestRun testRun = TestRun.mergeTestRuns(testDataWrapper.getTestRuns());
                if (testRun != null) {
                    dsb.add(testRun.getNumFailed(), "failed", new ChartUtil.NumberOnlyBuildLabel(run));
                    dsb.add(testRun.getNumDegraded(), "degraded", new ChartUtil.NumberOnlyBuildLabel(run));
                    dsb.add(testRun.getNumImproved(), "improved", new ChartUtil.NumberOnlyBuildLabel(run));
                    dsb.add(testRun.getNumPassed(), "passed", new ChartUtil.NumberOnlyBuildLabel(run));
                    dsb.add(testRun.getNumVolatile(), "volatile", new ChartUtil.NumberOnlyBuildLabel(run));
                    dsb.add(testRun.getNumInvalidated(), "invalidated", new ChartUtil.NumberOnlyBuildLabel(run));
                }
            }
            if (buildCount != 0 && i == buildCount) break;
            i++;
        }
        return dsb.build();
    }

    private JFreeChart createTestRunChart(final CategoryDataset dataset, final String customName) {
        String title = "Unit Test Overview";
        if (StringUtils.isNotBlank(customName)) {
            title = customName;
        }

        final JFreeChart chart = ChartFactory.createBarChart(title, // title
                "Build", // category axis label
                "num", // value axis label
                dataset, // data
                PlotOrientation.VERTICAL, // orientation
                true, // include legend
                true, // tooltips
                false // urls
        );

        chart.setBackgroundPaint(Color.white);

        final CategoryPlot plot = chart.getCategoryPlot();

        plot.setBackgroundPaint(Color.WHITE);
        plot.setOutlinePaint(null);
        plot.setForegroundAlpha(0.8f);
        plot.setRangeGridlinesVisible(true);
        plot.setRangeGridlinePaint(Color.black);

        final CategoryAxis domainAxis = new ShiftedCategoryAxis(null);
        plot.setDomainAxis(domainAxis);
        domainAxis.setCategoryLabelPositions(CategoryLabelPositions.UP_90);
        //domainAxis.setLowerMargin(0.0);
        //domainAxis.setUpperMargin(0.0);
        //domainAxis.setCategoryMargin(0.0);

        final NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setStandardTickUnits(NumberAxis.createIntegerTickUnits());

        final StackedBarRenderer br = new StackedBarRenderer();
        plot.setRenderer(br);
        br.setSeriesPaint(0, new Color(0xFF, 0x99, 0x99)); // degraded
        br.setSeriesPaint(1, ColorPalette.RED); // failed
        br.setSeriesPaint(2, new Color(0x00, 0xFF, 0x00)); // improved
        br.setSeriesPaint(3, ColorPalette.GREY); // invalidated
        br.setSeriesPaint(4, ColorPalette.BLUE); // passed
        br.setSeriesPaint(5, ColorPalette.YELLOW); // volatile

        return chart;
    }

    private Area calcDefaultSize() {
        Area res = Functions.getScreenResolution();
        if (res != null && res.width <= 800)
            return new Area(250, 100);
        else
            return new Area(500, 200);
    }

    public List<DashboardReport> getLastDashboardReports() {
        final Run<?, ?> tb = project.getLastSuccessfulBuild();

        Run<?, ?> b = project.getLastBuild();
        while (b != null) {
            PerfSigBuildAction a = b.getAction(PerfSigBuildAction.class);
            if (a != null && (!b.isBuilding())) return a.getDashboardReports();
            if (b == tb)
                return null;
            b = b.getPreviousBuild();
        }
        return null;
    }

    public TestRun getTestRun(final int buildNumber) {
        final Run run = project.getBuildByNumber(buildNumber);
        if (run != null) {
            PerfSigTestDataWrapper testDataWrapper = run.getAction(PerfSigTestDataWrapper.class);
            if (testDataWrapper != null) {
                return TestRun.mergeTestRuns(testDataWrapper.getTestRuns());
            }
        }
        return null;
    }

    public TestResult getTestAction(final int buildNumber) {
        final Run run = project.getBuildByNumber(buildNumber);
        if (run != null) {
            TestResultAction testResultAction = run.getAction(TestResultAction.class);
            if (testResultAction != null) {
                return testResultAction.getResult();
            }
        }
        return null;
    }

    public List<DashboardReport> getDashBoardReports(final String tc) {
        final List<DashboardReport> dashboardReportList = new ArrayList<DashboardReport>();
        if (project == null) {
            return dashboardReportList;
        }
        final List<? extends AbstractBuild> builds = project.getBuilds();
        for (AbstractBuild currentBuild : builds) {
            final PerfSigBuildAction performanceBuildAction = currentBuild.getAction(PerfSigBuildAction.class);
            if (performanceBuildAction != null) {
                DashboardReport dashboardReport = performanceBuildAction.getBuildActionResultsDisplay().getDashBoardReport(tc);
                if (dashboardReport == null) {
                    dashboardReport = new DashboardReport(tc);
                    dashboardReport.setBuildAction(new PerfSigBuildAction(currentBuild, null));
                }
                dashboardReportList.add(dashboardReport);
            }
        }
        return dashboardReportList;
    }

    public void doDownloadFile(final StaplerRequest request, final StaplerResponse response) throws IOException {
        final Pattern pattern = Pattern.compile("-\\d+");
        final Matcher matcher = pattern.matcher(request.getParameter("f"));
        if (matcher.find()) {
            final int id = Integer.parseInt(matcher.group().substring(1));
            PerfSigUtils.downloadFile(request, response, project.getBuildByNumber(id));
        }
    }

    private FilePath getJsonConfigFilePath() {
        final FilePath configPath = new FilePath(project.getConfigFile().getFile());
        return configPath.getParent();
    }

    private String getDashboardConfiguration() throws IOException, InterruptedException {
        final List<FilePath> fileList = getJsonConfigFilePath().list(new RegexFileFilter("gridconfig-.*.json"));
        final StringBuilder sb = new StringBuilder("[");
        for (FilePath file : fileList) {
            final String tmp = file.readToString();
            sb.append(tmp.substring(1, tmp.length() - 1)).append(",");
        }
        sb.setLength(sb.length() - 1);
        sb.append("]");
        return sb.toString();
    }

    private String getDashboardConfiguration(final String dashboard) throws IOException, InterruptedException {
        final FilePath input = new FilePath(new File(getJsonConfigFilePath() + File.separator + "gridconfig-" + dashboard + ".json"));
        if (!input.exists()) input.write(createJSONConfigString(), null);
        return input.readToString();
    }

    public void doGetDashboardConfiguration(final StaplerRequest request, final StaplerResponse response) throws IOException, InterruptedException {
        final String dashboard = request.getParameter("dashboard");
        if(StringUtils.isBlank(dashboard)) return;
        String output = getDashboardConfiguration(dashboard);
        response.getOutputStream().print(output);
    }

    private String createJSONConfigString() {
        int col = 1, row = 1;
        JSONArray array = new JSONArray();
        for (DashboardReport dashboardReport : getLastDashboardReports()) {
            if (dashboardReport.isUnitTest()) {
                JSONObject obj = new JSONObject();
                obj.put("id", "unittest_overview");
                obj.put("col", col++);
                obj.put("row", row);
                obj.put("dashboard", dashboardReport.getName());
                obj.put("chartDashlet", "");
                obj.put("measure", "");
                obj.put("show", true);
                obj.put("customName", "");
                obj.put("customBuildCount", 0);

                array.add(obj);
            }
            for (ChartDashlet chartDashlet : dashboardReport.getChartDashlets()) {
                for (Measure measure : chartDashlet.getMeasures()) {
                    JSONObject obj = new JSONObject();
                    obj.put("id", DigestUtils.md5Hex(dashboardReport.getName() + chartDashlet.getName() + measure.getName()));
                    obj.put("col", col++);
                    obj.put("row", row);
                    obj.put("dashboard", dashboardReport.getName());
                    obj.put("chartDashlet", chartDashlet.getName());
                    obj.put("measure", measure.getName());
                    obj.put("description", chartDashlet.getDescription());
                    obj.put("show", true);
                    obj.put("customName", "");
                    obj.put("customBuildCount", 0);

                    array.add(obj);

                    if (col > 3) {
                        col = 1;
                        row++;
                    }
                }
            }
        }
        return array.toString();
    }

    public void doSetDashboardConfiguration(final StaplerRequest request, final StaplerResponse response) {
        final String dashboard = request.getParameter("dashboard");
        final String data = request.getParameter("data");
        if(StringUtils.isBlank(dashboard) || StringUtils.isBlank("data")) return;

        final HashMap<String, MeasureNameHelper> map = new HashMap<String, MeasureNameHelper>();
        for (DashboardReport dashboardReport : getLastDashboardReports())
            if (dashboardReport.getName().equals(dashboard))
                for (ChartDashlet chartDashlet : dashboardReport.getChartDashlets())
                    for (Measure measure : chartDashlet.getMeasures())
                        map.put(DigestUtils.md5Hex(dashboardReport.getName() + chartDashlet.getName() + measure.getName()),
                                new MeasureNameHelper(chartDashlet.getName(), measure.getName(), chartDashlet.getDescription()));

        try {
            final JSONArray gridConfiguration = JSONArray.fromObject(data);
            final JSONArray dashboardConfiguration = JSONArray.fromObject(getDashboardConfiguration(dashboard));
            for (int i = 0; i < gridConfiguration.size(); i++) {
                final JSONObject obj = gridConfiguration.getJSONObject(i);
                final MeasureNameHelper tmp = map.get(obj.getString("id"));
                if (tmp != null) {
                    obj.put("id", DigestUtils.md5Hex(dashboard + tmp.chartDashlet + tmp.measure + obj.getString("customName")));
                    obj.put("chartDashlet", tmp.chartDashlet);
                    obj.put("measure", tmp.measure);
                    obj.put("description", tmp.description);
                } else {
                    for (int j = 0; j < dashboardConfiguration.size(); j++) {
                        final JSONObject jsonObject = dashboardConfiguration.getJSONObject(j);
                        if (jsonObject.get("id").equals(obj.get("id"))) {
                            if (!obj.get("id").equals("unittest_overview")) {
                                obj.put("dashboard", jsonObject.get("dashboard"));
                                obj.put("chartDashlet", jsonObject.get("chartDashlet"));
                                obj.put("measure", jsonObject.get("measure"));
                                obj.put("description", jsonObject.get("description"));
                            }
                            if (StringUtils.isNotBlank(jsonObject.getString("customName")))
                                obj.put("customName", jsonObject.get("customName"));
                            if (StringUtils.isNotBlank(jsonObject.getString("customBuildCount")))
                                obj.put("customBuildCount", jsonObject.get("customBuildCount"));
                            break;
                        }
                    }
                }
            }

            FilePath output = new FilePath(new File(getJsonConfigFilePath() + File.separator + "gridconfig-" + dashboard + ".json"));
            output.write(gridConfiguration.toString(), null);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void doGetAvailableMeasures(final StaplerRequest request, final StaplerResponse response) throws IOException {
        final String dashboard = request.getParameter("dashboard");
        final String dashlet = request.getParameter("dashlet");
        if(StringUtils.isBlank(dashboard) || StringUtils.isBlank(dashlet)) return;

        final Map<String, String> availableMeasures = new HashMap<String, String>();
        if (StringUtils.isNotBlank(dashlet)) {
            mainLoop:
            for (DashboardReport dashboardReport : getLastDashboardReports()) {
                if (dashboardReport.getName().equals(dashboard)) {
                    for (ChartDashlet chartDashlet : dashboardReport.getChartDashlets()) {
                        if (chartDashlet.getName().equals(dashlet)) {
                            for (Measure measure : chartDashlet.getMeasures())
                                availableMeasures.put(DigestUtils.md5Hex(dashboardReport.getName() + chartDashlet.getName() + measure.getName()), measure.getName());
                            break mainLoop;
                        }
                    }
                }
            }
        }
        JSONObject jsonObject = JSONObject.fromObject(availableMeasures);
        jsonObject.write(response.getWriter());
        response.flushBuffer();
    }

    public List<ChartDashlet> getFilteredChartDashlets(final DashboardReport dashboardReport) throws IOException, InterruptedException {
        final List<ChartDashlet> chartDashlets = new ArrayList<ChartDashlet>();
        final String json = getDashboardConfiguration(dashboardReport.getName());
        if (StringUtils.isBlank(json) || dashboardReport.getChartDashlets() == null) return chartDashlets;
        final JSONArray jsonArray = JSONArray.fromObject(json);

        for (int i = 0; i < jsonArray.size(); i++) {
            JSONObject obj = jsonArray.getJSONObject(i);
            String measure = obj.getString("measure");
            String chartDashlet = obj.getString("chartDashlet");

            for (ChartDashlet dashlet : dashboardReport.getChartDashlets()) {
                if (dashlet.getName().equals(chartDashlet)) {
                    for (Measure m : dashlet.getMeasures()) {
                        if (m.getName().equals(measure)) {
                            ChartDashlet d;
                            if (StringUtils.isBlank(obj.getString("customName")))
                                d = new ChartDashlet(PerfSigUtils.generateTitle(m.getName(), dashlet.getName()));
                            else
                                d = new ChartDashlet(obj.getString("customName"));
                            d.addMeasure(m);
                            chartDashlets.add(d);
                            break;
                        }
                    }
                }
            }
        }
        return chartDashlets;
    }
}