package org.mlflow.tracking;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.testng.Assert;
import org.testng.annotations.*;

import static org.mlflow.tracking.TestUtils.*;

import org.mlflow.api.proto.Service.*;
import org.mlflow.artifacts.ArtifactRepository;

public class MlflowClientTest {
  private static final Logger logger = Logger.getLogger(MlflowClientTest.class);

  private static float ACCURACY_SCORE = 0.9733333333333334F;
  private static float ZERO_ONE_LOSS = 0.026666666666666616F;
  private static String MIN_SAMPLES_LEAF = "2";
  private static String MAX_DEPTH = "3";

  private final TestClientProvider testClientProvider = new TestClientProvider();
  private String runId;

  private MlflowClient client;

  @BeforeSuite
  public void beforeAll() throws IOException {
    client = testClientProvider.initializeClientAndServer();
  }

  @AfterSuite
  public void afterAll() throws InterruptedException {
    testClientProvider.cleanupClientAndServer();
  }

  @Test
  public void getCreateExperimentTest() {
    String expName = createExperimentName();
    long expId = client.createExperiment(expName);
    GetExperiment.Response exp = client.getExperiment(expId);
    Assert.assertEquals(exp.getExperiment().getName(), expName);
  }

  @Test(expectedExceptions = MlflowClientException.class) // TODO: server should throw 406
  public void createExistingExperiment() {
    String expName = createExperimentName();
    client.createExperiment(expName);
    client.createExperiment(expName);
  }

  @Test
  public void listExperimentsTest() {
    List<Experiment> expsBefore = client.listExperiments();

    String expName = createExperimentName();
    long expId = client.createExperiment(expName);

    List<Experiment> exps = client.listExperiments();
    Assert.assertEquals(exps.size(), 1 + expsBefore.size());

    java.util.Optional<Experiment> opt = getExperimentByName(exps, expName);
    Assert.assertTrue(opt.isPresent());
    Experiment expList = opt.get();
    Assert.assertEquals(expList.getName(), expName);

    GetExperiment.Response expGet = client.getExperiment(expId);
    Assert.assertEquals(expGet.getExperiment(), expList);
  }

  @Test
  public void addGetRun() {
    // Create exp
    String expName = createExperimentName();
    long expId = client.createExperiment(expName);
    logger.debug(">> TEST.0");

    // Create run
    String user = System.getenv("USER");
    long startTime = System.currentTimeMillis();
    String sourceFile = "MyFile.java";

    RunInfo runCreated = client.createRun(expId, sourceFile);
    runId = runCreated.getRunUuid();
    logger.debug("runId=" + runId);

    List<RunInfo> runInfos = client.listRunInfos(expId);
    Assert.assertEquals(runInfos.size(), 1);
    Assert.assertEquals(runInfos.get(0).getSourceType(), SourceType.LOCAL);
    Assert.assertEquals(runInfos.get(0).getStatus(), RunStatus.RUNNING);

    // Log parameters
    client.logParam(runId, "min_samples_leaf", MIN_SAMPLES_LEAF);
    client.logParam(runId, "max_depth", MAX_DEPTH);

    // Log metrics
    client.logMetric(runId, "accuracy_score", ACCURACY_SCORE);
    client.logMetric(runId, "zero_one_loss", ZERO_ONE_LOSS);

    // Update finished run
    client.setTerminated(runId, RunStatus.FINISHED, startTime + 1001);

    List<RunInfo> updatedRunInfos = client.listRunInfos(expId);
    Assert.assertEquals(updatedRunInfos.size(), 1);
    Assert.assertEquals(updatedRunInfos.get(0).getStatus(), RunStatus.FINISHED);

    // Assert run from getExperiment
    GetExperiment.Response expResponse = client.getExperiment(expId);
    Experiment exp = expResponse.getExperiment();
    Assert.assertEquals(exp.getName(), expName);

    // Assert run from getRun
    Run run = client.getRun(runId);
    RunInfo runInfo = run.getInfo();
    assertRunInfo(runInfo, expId, sourceFile);
  }

  @Test(dependsOnMethods = {"addGetRun"})
  public void checkParamsAndMetrics() {

    Run run = client.getRun(runId);
    List<Param> params = run.getData().getParamsList();
    Assert.assertEquals(params.size(), 2);
    assertParam(params, "min_samples_leaf", MIN_SAMPLES_LEAF);
    assertParam(params, "max_depth", MAX_DEPTH);

    List<Metric> metrics = run.getData().getMetricsList();
    Assert.assertEquals(metrics.size(), 2);
    assertMetric(metrics, "accuracy_score", ACCURACY_SCORE);
    assertMetric(metrics, "zero_one_loss", ZERO_ONE_LOSS);
    assert(metrics.get(0).getTimestamp() > 0) : metrics.get(0).getTimestamp();
  }

  @Test
  public void testUseArtifactRepository() throws IOException {
    String content = "Hello, Worldz!";

    File tempFile = Files.createTempFile(getClass().getSimpleName(), ".txt").toFile();
    FileUtils.writeStringToFile(tempFile, content, StandardCharsets.UTF_8);
    client.logArtifact(runId, tempFile);

    File downloadedArtifact = client.downloadArtifacts(runId, tempFile.getName());
    String downloadedContent = FileUtils.readFileToString(downloadedArtifact,
      StandardCharsets.UTF_8);
    Assert.assertEquals(content, downloadedContent);
  }
}
