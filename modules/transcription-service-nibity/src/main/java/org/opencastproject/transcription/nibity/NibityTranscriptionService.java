/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */
package org.opencastproject.transcription.nibity;

import org.opencastproject.assetmanager.api.AssetManager;
import org.opencastproject.assetmanager.api.fn.Enrichments;
import org.opencastproject.assetmanager.api.query.AQueryBuilder;
import org.opencastproject.assetmanager.api.query.AResult;
import org.opencastproject.assetmanager.util.Workflows;
import org.opencastproject.job.api.AbstractJobProducer;
import org.opencastproject.job.api.Job;
import org.opencastproject.kernel.mail.SmtpService;
import org.opencastproject.mediapackage.Attachment;
import org.opencastproject.mediapackage.MediaPackageElement;
import org.opencastproject.mediapackage.MediaPackageElementBuilder;
import org.opencastproject.mediapackage.MediaPackageElementBuilderFactory;
import org.opencastproject.mediapackage.MediaPackageElementFlavor;
import org.opencastproject.mediapackage.MediaPackageElementParser;
import org.opencastproject.mediapackage.MediaPackageException;
import org.opencastproject.mediapackage.Track;
import org.opencastproject.security.api.DefaultOrganization;
import org.opencastproject.security.api.Organization;
import org.opencastproject.security.api.OrganizationDirectoryService;
import org.opencastproject.security.api.SecurityService;
import org.opencastproject.security.api.UserDirectoryService;
import org.opencastproject.security.util.SecurityUtil;
import org.opencastproject.serviceregistry.api.ServiceRegistry;
import org.opencastproject.serviceregistry.api.ServiceRegistryException;
import org.opencastproject.systems.OpencastConstants;
import org.opencastproject.transcription.api.TranscriptionService;
import org.opencastproject.transcription.api.TranscriptionServiceException;
import org.opencastproject.transcription.nibity.persistence.NibityTranscriptionDatabase;
import org.opencastproject.transcription.nibity.persistence.NibityTranscriptionDatabaseException;
import org.opencastproject.transcription.nibity.persistence.NibityTranscriptionJobControl;
import org.opencastproject.util.OsgiUtil;
import org.opencastproject.util.PathSupport;
import org.opencastproject.util.data.Option;
import org.opencastproject.workflow.api.ConfiguredWorkflow;
import org.opencastproject.workflow.api.WorkflowDefinition;
import org.opencastproject.workflow.api.WorkflowInstance;
import org.opencastproject.workflow.api.WorkflowService;
import org.opencastproject.workingfilerepository.api.WorkingFileRepository;
import org.opencastproject.workspace.api.Workspace;

import org.apache.commons.io.FilenameUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class NibityTranscriptionService extends AbstractJobProducer implements TranscriptionService {

  /**
   * The logger
   */
  private static final Logger logger = LoggerFactory.getLogger(NibityTranscriptionService.class);

  private static final String JOB_TYPE = "org.opencastproject.transcription.nibity";

  static final String TRANSCRIPT_COLLECTION = "transcripts";
  private static final int CONNECTION_TIMEOUT = 60000; // ms, 1 minute
  private static final int SOCKET_TIMEOUT = 60000; // ms, 1 minute
  private static final int ACCESS_TOKEN_MINIMUM_TIME = 60000; // ms , 1 minute
  // Default wf to attach transcription results to mp
  public static final String DEFAULT_WF_DEF = "attach-nibity-transcripts";
  private static final long DEFAULT_COMPLETION_BUFFER = 300; // in seconds, default is 5 minutes
  private static final long DEFAULT_DISPATCH_INTERVAL = 60; // in seconds, default is 1 minute
  private static final long DEFAULT_MAX_PROCESSING_TIME = 5 * 60 * 60; // in seconds, default is 5 hours
  // Cleans up results files that are older than 7 days
  private static final int DEFAULT_CLEANUP_RESULTS_DAYS = 7;
  private static final boolean DEFAULT_PROFANITY_FILTER = false;
  private static final String DEFAULT_LANGUAGE = "en-US";

  // Nibity API
  private static final String GOOGLE_SPEECH_URL = "https://speech.googleapis.com/v1";
  private static final String GOOGLE_AUTH2_URL = "https://www.googleapis.com/oauth2/v4/token";
  private static final String REQUEST_PATH = "/speech:longrunningrecognize";
  private static final String RESULT_PATH = "/operations/";
  private static final String INVALID_TOKEN = "-1";
  private static final String PROVIDER = "Nibity";

  // Global configuration (custom.properties)
  public static final String ADMIN_URL_PROPERTY = "org.opencastproject.admin.ui.url";
  private static final String ADMIN_EMAIL_PROPERTY = "org.opencastproject.admin.email";
  private static final String DIGEST_USER_PROPERTY = "org.opencastproject.security.digest.user";

  // Cluster name
  private static final String CLUSTER_NAME_PROPERTY = "org.opencastproject.environment.name";
  private String clusterName = "";

  /**
   * Service dependencies
   */
  private ServiceRegistry serviceRegistry;
  private SecurityService securityService;
  private UserDirectoryService userDirectoryService;
  private OrganizationDirectoryService organizationDirectoryService;
  private Workspace workspace;
  private NibityTranscriptionDatabase database;
  private AssetManager assetManager;
  private WorkflowService workflowService;
  private WorkingFileRepository wfr;
  private SmtpService smtpService;

  // Only used by unit tests!
  private Workflows wfUtil;

  private enum Operation {
    StartTranscription
  }

  /**
   * Service configuration options
   */
  public static final String ENABLED_CONFIG = "enabled";
  public static final String NIBITY_LANGUAGE = "nibity.language";
  public static final String WORKFLOW_CONFIG = "workflow";
  public static final String DISPATCH_WORKFLOW_INTERVAL_CONFIG = "workflow.dispatch.interval";
  public static final String COMPLETION_CHECK_BUFFER_CONFIG = "completion.check.buffer";
  public static final String MAX_PROCESSING_TIME_CONFIG = "max.processing.time";
  public static final String NOTIFICATION_EMAIL_CONFIG = "notification.email";
  public static final String CLEANUP_RESULTS_DAYS_CONFIG = "cleanup.results.days";
  public static final String GOOGLE_CLOUD_CLIENT_ID = "google.cloud.client.id";
  public static final String GOOGLE_CLOUD_CLIENT_SECRET = "google.cloud.client.secret";
  public static final String GOOGLE_CLOUD_REFRESH_TOKEN = "google.cloud.refresh.token";
  public static final String GOOGLE_CLOUD_BUCKET = "google.cloud.storage.bucket";
  public static final String GOOGLE_CLOUD_TOKEN_ENDPOINT_URL = "google.cloud.token.endpoint.url";
  public static final String NIBITY_CLIENT_ID = "nibity.client.id";
  public static final String NIBITY_CLIENT_KEY = "nibity.client.key";

  /**
   * Service configuration values
   */
  private boolean enabled = false; // Disabled by default
  private String language = DEFAULT_LANGUAGE;
  private String workflowDefinitionId = DEFAULT_WF_DEF;
  private long workflowDispatchInterval = DEFAULT_DISPATCH_INTERVAL;
  private long completionCheckBuffer = DEFAULT_COMPLETION_BUFFER;
  private long maxProcessingSeconds = DEFAULT_MAX_PROCESSING_TIME;
  private String toEmailAddress;
  private int cleanupResultDays = DEFAULT_CLEANUP_RESULTS_DAYS;
  private String clientId;
  private String clientSecret;
  private String clientToken;
  private String nibityClientId;
  private String nibityClientKey;
  private String accessToken = INVALID_TOKEN;
  private String tokenEndpoint = GOOGLE_AUTH2_URL;
  private String storageBucket;
  private long tokenExpiryTime = 0;
  private String systemAccount;
  private String serverUrl;
  private ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(2);

  public NibityTranscriptionService() {
    super(JOB_TYPE);
  }

  public void activate(ComponentContext cc) {
    if (cc != null) {
      // Has this service been enabled?
      enabled = OsgiUtil.getOptCfgAsBoolean(cc.getProperties(), ENABLED_CONFIG).get();

      if (enabled) {
        // Mandatory API access properties
        clientId = OsgiUtil.getComponentContextProperty(cc, GOOGLE_CLOUD_CLIENT_ID);
        clientSecret = OsgiUtil.getComponentContextProperty(cc, GOOGLE_CLOUD_CLIENT_SECRET);
        clientToken = OsgiUtil.getComponentContextProperty(cc, GOOGLE_CLOUD_REFRESH_TOKEN);
        storageBucket = OsgiUtil.getComponentContextProperty(cc, GOOGLE_CLOUD_BUCKET);

        // access token endpoint
        Option<String> tokenOpt = OsgiUtil.getOptCfg(cc.getProperties(), GOOGLE_CLOUD_TOKEN_ENDPOINT_URL);
        if (tokenOpt.isSome()) {
          tokenEndpoint = tokenOpt.get();
          logger.info("Access token endpoint is set to {}", tokenEndpoint);
        } else {
          logger.info("Default access token endpoint will be used");
        }

        // Nibity API client ID and key
        nibityClientId = OsgiUtil.getComponentContextProperty(cc, NIBITY_CLIENT_ID);
        nibityClientKey = OsgiUtil.getComponentContextProperty(cc, NIBITY_CLIENT_KEY);

        // TODO remove for production
        logger.info("Nibity API auth details: client id {} key {}", nibityClientId, nibityClientKey);

        // Language model to be used
        Option<String> languageOpt = OsgiUtil.getOptCfg(cc.getProperties(), NIBITY_LANGUAGE);
        if (languageOpt.isSome()) {
          language = languageOpt.get();
          logger.info("Language used is {}", language);
        } else {
          logger.info("Default language will be used");
        }
        // Workflow to execute when getting callback (optional, with default)
        Option<String> wfOpt = OsgiUtil.getOptCfg(cc.getProperties(), WORKFLOW_CONFIG);
        if (wfOpt.isSome()) {
          workflowDefinitionId = wfOpt.get();
        }
        logger.info("Workflow definition is {}", workflowDefinitionId);
        // Interval to check for completed transcription jobs and start workflows to attach transcripts
        Option<String> intervalOpt = OsgiUtil.getOptCfg(cc.getProperties(), DISPATCH_WORKFLOW_INTERVAL_CONFIG);
        if (intervalOpt.isSome()) {
          try {
            workflowDispatchInterval = Long.parseLong(intervalOpt.get());
          } catch (NumberFormatException e) {
            // Use default
          }
        }
        logger.info("Workflow dispatch interval is {} seconds", workflowDispatchInterval);
        // How long to wait after a transcription is supposed to finish before starting checking
        Option<String> bufferOpt = OsgiUtil.getOptCfg(cc.getProperties(), COMPLETION_CHECK_BUFFER_CONFIG);
        if (bufferOpt.isSome()) {
          try {
            completionCheckBuffer = Long.parseLong(bufferOpt.get());
          } catch (NumberFormatException e) {
            // Use default
            logger.warn("Invalid configuration for {} : {}. Default used instead: {}",
                    new Object[]{COMPLETION_CHECK_BUFFER_CONFIG, bufferOpt.get(), completionCheckBuffer});
          }
        }
        logger.info("Completion check buffer is {} seconds", completionCheckBuffer);
        // How long to wait after a transcription is supposed to finish before marking the job as canceled in the db
        Option<String> maxProcessingOpt = OsgiUtil.getOptCfg(cc.getProperties(), MAX_PROCESSING_TIME_CONFIG);
        if (maxProcessingOpt.isSome()) {
          try {
            maxProcessingSeconds = Long.parseLong(maxProcessingOpt.get());
          } catch (NumberFormatException e) {
            // Use default
          }
        }
        logger.info("Maximum time a job is checked after it should have ended is {} seconds", maxProcessingSeconds);
        // How long to keep result files in the working file repository
        Option<String> cleaupOpt = OsgiUtil.getOptCfg(cc.getProperties(), CLEANUP_RESULTS_DAYS_CONFIG);
        if (cleaupOpt.isSome()) {
          try {
            cleanupResultDays = Integer.parseInt(cleaupOpt.get());
          } catch (NumberFormatException e) {
            // Use default
          }
        }
        logger.info("Cleanup result files after {} days", cleanupResultDays);

        serverUrl = OsgiUtil.getContextProperty(cc, OpencastConstants.SERVER_URL_PROPERTY);
        systemAccount = OsgiUtil.getContextProperty(cc, DIGEST_USER_PROPERTY);

        // Schedule the workflow dispatching, starting in 2 minutes
        scheduledExecutor.scheduleWithFixedDelay(new WorkflowDispatcher(), 120, workflowDispatchInterval,
                TimeUnit.SECONDS);

        // Schedule the cleanup of old results jobs from the collection in the wfr once a day
        scheduledExecutor.scheduleWithFixedDelay(new ResultsFileCleanup(), 1, 1, TimeUnit.DAYS);

        // Notification email passed in this service configuration?
        Option<String> optTo = OsgiUtil.getOptCfg(cc.getProperties(), NOTIFICATION_EMAIL_CONFIG);
        if (optTo.isSome()) {
          toEmailAddress = optTo.get();
        } else {
          // Use admin email informed in custom.properties
          optTo = OsgiUtil.getOptContextProperty(cc, ADMIN_EMAIL_PROPERTY);
          if (optTo.isSome()) {
            toEmailAddress = optTo.get();
          }
        }
        if (toEmailAddress != null) {
          logger.info("Notification email set to {}", toEmailAddress);
        } else {
          logger.warn("Email notification disabled");
        }

        Option<String> optCluster = OsgiUtil.getOptContextProperty(cc, CLUSTER_NAME_PROPERTY);
        if (optCluster.isSome()) {
          clusterName = optCluster.get();
        }
        logger.info("Environment name is {}", clusterName);

        logger.info("Activated!");
        // Cannot call registerCallback here because of the REST service dependency on this service
      } else {
        logger.info("Service disabled. If you want to enable it, please update the service configuration.");
      }
    } else {
      throw new IllegalArgumentException("Missing component context");
    }
  }

  @Override
  public Job startTranscription(String mpId, Track track) throws TranscriptionServiceException {
    if (!enabled) {
      throw new TranscriptionServiceException(
              "This service is disabled. If you want to enable it, please update the service configuration.");
    }

    try {
      return serviceRegistry.createJob(JOB_TYPE, Operation.StartTranscription.name(),
              Arrays.asList(mpId, MediaPackageElementParser.getAsXml(track), language));
    } catch (ServiceRegistryException e) {
      throw new TranscriptionServiceException("Unable to create a job", e);
    } catch (MediaPackageException e) {
      throw new TranscriptionServiceException("Invalid track " + track.toString(), e);
    }
  }

  @Override
  public void transcriptionDone(String mpId, Object obj) throws TranscriptionServiceException {
    JSONObject jsonObj = null;
    String jobId = null;
    String token = INVALID_TOKEN;
    try {
      token = getRefreshAccessToken();
    } catch (IOException ex) {
      logger.error("Unable to create access token, error: {}", ex.toString());
    }
    if (token.equals(INVALID_TOKEN)) {
      throw new TranscriptionServiceException("Invalid access token");
    }
    try {
      jsonObj = (JSONObject) obj;
      jobId = (String) jsonObj.get("name");
      logger.info("Transcription done for mpId {}, jobId {}", mpId, jobId);
      JSONArray resultsArray = getTranscriptionResult(jsonObj);

      // Update state in database
      // If there's an optimistic lock exception here, it's ok because the workflow dispatcher
      // may be doing the same thing
      database.updateJobControl(jobId, NibityTranscriptionJobControl.Status.TranscriptionComplete.name());

      // Delete media file from Google storage
      deleteStorageFile(mpId, token);

      // Save results in file system if there exist
      if (resultsArray != null) {
        saveResults(jobId, jsonObj);
      }
    } catch (IOException e) {
      logger.warn("Could not save transcription results file for mpId {}, jobId {}: {}",
              new Object[]{mpId, jobId, jsonObj == null ? "null" : jsonObj.toJSONString()});
      throw new TranscriptionServiceException("Could not save transcription results file", e);
    } catch (NibityTranscriptionDatabaseException e) {
      logger.warn("Transcription results file were saved but state in db not updated for mpId {}, jobId {}", mpId,
              jobId);
      throw new TranscriptionServiceException("Could not update transcription job control db", e);
    }
  }

  @Override
  public void transcriptionError(String mpId, Object obj) throws TranscriptionServiceException {
    JSONObject jsonObj = null;
    String jobId = null;
    try {
      jsonObj = (JSONObject) obj;
      jobId = (String) jsonObj.get("name");
      // Update state in database
      database.updateJobControl(jobId, NibityTranscriptionJobControl.Status.Error.name());
      NibityTranscriptionJobControl jobControl = database.findByJob(jobId);
      logger.warn(String.format("Error received for media package %s, job id %s",
              jobControl.getMediaPackageId(), jobId));
      // Send notification email
      sendEmail("Transcription ERROR",
              String.format("There was a transcription error for for media package %s, job id %s.",
                      jobControl.getMediaPackageId(), jobId));
    } catch (NibityTranscriptionDatabaseException e) {
      logger.warn("Transcription error. State in db could not be updated to error for mpId {}, jobId {}", mpId, jobId);
      throw new TranscriptionServiceException("Could not update transcription job control db", e);
    }
  }

 @Override
  public String getLanguage() {
    return language;
  }

  @Override
  protected String process(Job job) throws Exception {
    Operation op = null;
    String operation = job.getOperation();
    List<String> arguments = job.getArguments();
    String result = "";
    op = Operation.valueOf(operation);
    switch (op) {
      case StartTranscription:
        String mpId = arguments.get(0);
        Track track = (Track) MediaPackageElementParser.getFromXml(arguments.get(1));
        String languageCode = arguments.get(2);
        createRecognitionsJob(mpId, track, languageCode);
        break;
      default:
        throw new IllegalStateException("Don't know how to handle operation '" + operation + "'");
    }
    return result;
  }

  /**
   * Asynchronous Requests and Responses call to Nibity API
   * https://documenter.getpostman.com/view/5815470/RznFpyb6
   * https://api.nibity.com/v1/{id}/submit
   */
  void createRecognitionsJob(String mpId, Track track, String languageCode) throws TranscriptionServiceException, IOException {

    String mediaUrl = uploadMediaFileToGoogleStorage(mpId, track);

    if (mediaUrl == null) {
      throw new TranscriptionServiceException("Could not create recognition job: unable to upload media to storage");
    }

    logger.info("Media URL in intermediate storage: {}", mediaUrl);

    CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
    credentialsProvider.setCredentials(AuthScope.ANY,
      new UsernamePasswordCredentials(nibityClientKey, ""));

    CloseableHttpClient httpClient = HttpClientBuilder.create().setDefaultCredentialsProvider(credentialsProvider).build();
    CloseableHttpResponse response = null;

    // Use default language if not set by workflow
    if (languageCode == null || languageCode.isEmpty()) {
      languageCode = language;
    }

    // Create json for configuration and media file 
    JSONObject configValues = new JSONObject();
    JSONObject audioValues = new JSONObject();
    JSONObject container = new JSONObject();
    configValues.put("languageCode", languageCode);
    configValues.put("enableWordTimeOffsets", true);
    audioValues.put("uri", mediaUrl);
    container.put("config", configValues);
    container.put("audio", audioValues);

    String apiUrl = "https://api.nibity.com/v1/" + nibityClientId + "/submit";

    List <NameValuePair> nvps = new ArrayList<NameValuePair>();
    nvps.add(new BasicNameValuePair("media[0][name]", mpId));
    nvps.add(new BasicNameValuePair("media[0][url]", mediaUrl));
    nvps.add(new BasicNameValuePair("ref", "Test submission reference"));
    nvps.add(new BasicNameValuePair("notes", "Test submission notes"));
    nvps.add(new BasicNameValuePair("retain", "168"));

    try {
      HttpPost httpPost = new HttpPost(apiUrl);
      httpPost.setEntity(new UrlEncodedFormEntity(nvps, "UTF-8"));

      response = httpClient.execute(httpPost);
      int code = response.getStatusLine().getStatusCode();
      HttpEntity entity = response.getEntity();

      String jsonString = EntityUtils.toString(response.getEntity());
      JSONParser jsonParser = new JSONParser();
      JSONObject jsonObject = (JSONObject) jsonParser.parse(jsonString);

      switch (code) {
        case HttpStatus.SC_OK: // 200
          logger.info("Recognitions job has been successfully created");

          /**
           * Response returned is a json object: { "Earnings Call 2018": { "id": "2477", "length": 3218.797, "status": 500 } }
           */
          JSONObject result = (JSONObject) jsonObject.get(mpId);

          String jobId = (String) result.get("id");
          long jobStatus = (Long) result.get("status");

          logger.info(String.format(
                  "Transcription for mp %s has been submitted. Job id: %s status %s", mpId, jobId, jobStatus));

          if (jobStatus == 500) {
              database.storeJobControl(mpId, track.getIdentifier(), jobId, NibityTranscriptionJobControl.Status.Progress.name(),
                  track.getDuration() == null ? 0 : track.getDuration().longValue(), PROVIDER);
              EntityUtils.consume(entity);
          } else {
              logger.warn("Unknown job status {} in JSON response: {}", jsonString);
          }
          return;

        default:
          logger.warn("Invalid argument returned, status: {} with message: {}", code, jsonString);
          break;
      }
      throw new TranscriptionServiceException("Could not create recognition job. Status returned: " + code);
    } catch (Exception e) {
      logger.warn("Exception when calling the recognitions endpoint", e);
      throw new TranscriptionServiceException("Exception when calling the recognitions endpoint", e);
    } finally {
      try {
        httpClient.close();
        if (response != null) {
          response.close();
        }
      } catch (IOException e) {
      }
    }
  }

  /**
   * Get transcription job result: GET /v1/operations/{name}
   *
   * "response": { "@type":
   * "type.googleapis.com/google.cloud.speech.v1.LongRunningRecognizeResponse",
   * "results": [ { "alternatives": [ { "transcript": "Four score and
   * twenty...", "confidence": 0.97186122, "words": [ { "startTime": "1.300s",
   * "endTime": "1.400s", "word": "Four" }, { "startTime": "1.400s", "endTime":
   * "1.600s", "word": "score" }, { "startTime": "1.600s", "endTime": "1.600s",
   * "word": "and" }, { "startTime": "1.600s", "endTime": "1.900s", "word":
   * "twenty" }, ] } ] }
   */
  boolean getAndSaveJobResults(String jobId) throws TranscriptionServiceException, IOException {
    CloseableHttpClient httpClient = makeHttpClient();
    CloseableHttpResponse response = null;
    String mpId = "unknown";
    JSONArray resultsArray = null;
    String token = getRefreshAccessToken();
    if (token.equals(INVALID_TOKEN)) {
      return false;
    }
    try {
      HttpGet httpGet = new HttpGet(GOOGLE_SPEECH_URL + RESULT_PATH + jobId);
      logger.debug("Url to invoke Google speech service: {}", httpGet.getURI().toString());
      // add the authorization header to the request;
      httpGet.addHeader("Authorization", "Bearer " + token);
      response = httpClient.execute(httpGet);
      int code = response.getStatusLine().getStatusCode();

      switch (code) {
        case HttpStatus.SC_OK: // 200
          HttpEntity entity = response.getEntity();
          // Response returned is a json object described above
          String jsonString = EntityUtils.toString(entity);
          JSONParser jsonParser = new JSONParser();
          JSONObject jsonObject = (JSONObject) jsonParser.parse(jsonString);
          Boolean jobDone = (Boolean) jsonObject.get("done");
          if (jobDone) {
            resultsArray = getTranscriptionResult(jsonObject);
          }
          NibityTranscriptionJobControl jc = database.findByJob(jobId);
          if (jc != null) {
            mpId = jc.getMediaPackageId();
          }
          logger.info("Recognitions job {} has been found, completed status {}", jobId, jobDone.toString());
          EntityUtils.consume(entity);

          if (jobDone && resultsArray != null) {
            transcriptionDone(mpId, jsonObject);
            return true;
          }
          return false;
        case HttpStatus.SC_NOT_FOUND: // 404
          logger.warn("Job not found: {}", jobId);
          break;
        case HttpStatus.SC_SERVICE_UNAVAILABLE: // 503
          logger.warn("Service unavailable returned, status: {}", code);
          break;
        default:
          logger.warn("Error return status: {}.", code);
          break;
      }
      throw new TranscriptionServiceException(
              String.format("Could not check recognition job for media package %s, job id %s. Status returned: %d",
                      mpId, jobId, code), code);
    } catch (TranscriptionServiceException e) {
      throw e;
    } catch (Exception e) {
      String msg = String.format("Exception when calling the recognitions endpoint for media package %s, job id %s",
              mpId, jobId);
      logger.warn(String.format(msg, mpId, jobId), e);
      throw new TranscriptionServiceException(String.format(
              "Exception when calling the recognitions endpoint for media package %s, job id %s", mpId, jobId), e);
    } finally {
      try {
        httpClient.close();
        if (response != null) {
          response.close();
        }
      } catch (IOException e) {
      }
    }
  }

  /**
   * Get transcription result: GET /v1/operations/{name} Method mainly used by
   * the REST endpoint
   *
   * @param jobId
   * @return job details
   * @throws org.opencastproject.transcription.api.TranscriptionServiceException
   * @throws java.io.IOException
   */
  public String getTranscriptionResults(String jobId)
          throws TranscriptionServiceException, IOException {
    CloseableHttpClient httpClient = makeHttpClient();
    CloseableHttpResponse response = null;
    String token = getRefreshAccessToken();
    if (token.equals(INVALID_TOKEN)) {
      logger.warn("Invalid access token");
      return "No results found";
    }
    try {
      HttpGet httpGet = new HttpGet(GOOGLE_SPEECH_URL + RESULT_PATH + jobId);
      logger.debug("Url to invoke Google speech service: {}", httpGet.getURI().toString());
      // add the authorization header to the request;
      httpGet.addHeader("Authorization", "Bearer " + token);
      response = httpClient.execute(httpGet);
      int code = response.getStatusLine().getStatusCode();

      switch (code) {
        case HttpStatus.SC_OK: // 200
          HttpEntity entity = response.getEntity();
          logger.info("Retrieved details for transcription with job id: '{}'", jobId);
          return EntityUtils.toString(entity);
        default:
          logger.warn("Error retrieving details for transcription with job id: '{}', return status: {}.", jobId, code);
          break;
      }
    } catch (Exception e) {
      String msg = String.format("Exception when calling the transcription service for job id: %s", jobId);
      logger.warn(String.format(msg, jobId), e);
      throw new TranscriptionServiceException(String.format(
              "Exception when calling the transcription service for job id: %s", jobId), e);
    } finally {
      try {
        httpClient.close();
        if (response != null) {
          response.close();
        }
      } catch (IOException e) {
      }
    }
    return "No results found";
  }

  private void saveResults(String jobId, JSONObject jsonObj) throws IOException {
    JSONArray resultsArray = getTranscriptionResult(jsonObj);
    if (resultsArray != null) {
      // Save the results into a collection
      workspace.putInCollection(TRANSCRIPT_COLLECTION, buildResultsFileName(jobId),
              new ByteArrayInputStream(jsonObj.toJSONString().getBytes()));
    }
  }

  @Override
  public MediaPackageElement getGeneratedTranscription(String mpId, String jobId)
          throws TranscriptionServiceException {
    try {
      // If jobId is unknown, look for all jobs associated to that mpId
      if (jobId == null || "null".equals(jobId)) {
        jobId = null;
        for (NibityTranscriptionJobControl jc : database.findByMediaPackage(mpId)) {
          if (NibityTranscriptionJobControl.Status.Closed.name().equals(jc.getStatus())
                  || NibityTranscriptionJobControl.Status.TranscriptionComplete.name().equals(jc.getStatus())) {
            jobId = jc.getTranscriptionJobId();
          }
        }
      }

      if (jobId == null) {
        throw new TranscriptionServiceException(
                "No completed or closed transcription job found in database for media package " + mpId);
      }

      // Results already saved?
      URI uri = workspace.getCollectionURI(TRANSCRIPT_COLLECTION, buildResultsFileName(jobId));
      try {
        workspace.get(uri);
      } catch (Exception e) {
        try {
          // Not saved yet so call the google speech service to get the results
          getAndSaveJobResults(jobId);
        } catch (IOException ex) {
          logger.error("Unable to retrieve transcription job, error: {}", ex.toString());
        }
      }
      MediaPackageElementBuilder builder = MediaPackageElementBuilderFactory.newInstance().newElementBuilder();
      return builder.elementFromURI(uri, Attachment.TYPE, new MediaPackageElementFlavor("captions", "google-speech-json"));
    } catch (NibityTranscriptionDatabaseException e) {
      throw new TranscriptionServiceException("Job id not informed and could not find transcription", e);
    }
  }

  /**
   * Get mediapackage transcription status
   *
   * @param mpId, mediapackage id
   * @return transcription status
   * @throws TranscriptionServiceException
   */
  public String getTranscriptionStatus(String mpId) throws TranscriptionServiceException {
    try {
      for (NibityTranscriptionJobControl jc : database.findByMediaPackage(mpId)) {
        return jc.getStatus();
      }
    } catch (NibityTranscriptionDatabaseException e) {
      throw new TranscriptionServiceException("Mediapackage id transcription status unknown", e);
    }
    return "Unknown";
  }

  protected CloseableHttpClient makeHttpClient() throws IOException {
    RequestConfig reqConfig = RequestConfig.custom().setConnectTimeout(CONNECTION_TIMEOUT)
            .setSocketTimeout(SOCKET_TIMEOUT).setConnectionRequestTimeout(CONNECTION_TIMEOUT).build();
    return HttpClients.custom().setDefaultRequestConfig(reqConfig)
            .build();
  }

  protected String refreshAccessToken(String clientId, String clientSecret, String refreshToken)
          throws TranscriptionServiceException, IOException {
    CloseableHttpClient httpClient = makeHttpClient();
    CloseableHttpResponse response = null;

    try {
      HttpPost httpPost = new HttpPost(tokenEndpoint + String.format(
              "?client_id=%s&client_secret=%s&refresh_token=%s&grant_type=refresh_token",
              clientId, clientSecret, refreshToken));
      httpPost.addHeader(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded");
      response = httpClient.execute(httpPost);
      int code = response.getStatusLine().getStatusCode();
      String jsonString = EntityUtils.toString(response.getEntity());
      JSONParser jsonParser = new JSONParser();
      JSONObject jsonObject = (JSONObject) jsonParser.parse(jsonString);
      switch (code) {
        case HttpStatus.SC_OK: // 200
          accessToken = (String) jsonObject.get("access_token");
          long duration = (long) jsonObject.get("expires_in"); // Duration in second
          tokenExpiryTime = (System.currentTimeMillis() + (duration * 1000)); // time in millisecond
          if (!INVALID_TOKEN.equals(accessToken)) {
            logger.info("Google Cloud Service access token created");
            return accessToken;
          }
          return INVALID_TOKEN;
        case HttpStatus.SC_BAD_REQUEST: // 400
        case HttpStatus.SC_UNAUTHORIZED: // 401
          String error = (String) jsonObject.get("error");
          String errorDetails = (String) jsonObject.get("error_description");
          logger.warn("Invalid argument returned, status: {}", code);
          logger.warn("Unable to refresh Google Cloud Serice token, error: {}, error details: {}", error, errorDetails);
          break;
        default:
          logger.warn("Invalid argument returned, status: {}", code);
      }
      throw new TranscriptionServiceException(
              String.format("Could not create Google access token. Status returned: %d", code), code);
    } catch (TranscriptionServiceException e) {
      throw e;
    } catch (Exception e) {
      logger.warn("Unable to generate access token for Google Cloud Services");
      return INVALID_TOKEN;
    } finally {
      try {
        httpClient.close();
        if (response != null) {
          response.close();
        }
      } catch (IOException e) {
      }
    }
  }

  protected String getRefreshAccessToken() throws TranscriptionServiceException, IOException {
    // Check that token hasn't expired
    if ((!INVALID_TOKEN.equals(accessToken)) && (System.currentTimeMillis() < (tokenExpiryTime - ACCESS_TOKEN_MINIMUM_TIME))) {
      return accessToken;
    }
    return refreshAccessToken(clientId, clientSecret, clientToken);
  }

  protected String uploadMediaFileToGoogleStorage(String mpId, Track track)
          throws TranscriptionServiceException, IOException {
    File mediaFile;
    String mediaUrl = null;
    String fileExtension;
    int mediaResponse;
    CloseableHttpClient httpClientStorage = makeHttpClient();
    NibityTranscriptionServiceStorage storage = new NibityTranscriptionServiceStorage();
    try {
      mediaFile = workspace.get(track.getURI());
      fileExtension = FilenameUtils.getExtension(mediaFile.getName());
      long fileSize = mediaFile.length();
      String contentType = track.getMimeType().toString();
      String token = getRefreshAccessToken();
      // Upload file to google cloud storage
      mediaResponse = storage.startUpload(httpClientStorage, storageBucket, mpId, fileExtension,
              mediaFile, String.valueOf(fileSize), contentType, token);
      if (mediaResponse == HttpStatus.SC_OK) {
        mediaUrl = String.format("gs://%s/%s.%s", storageBucket, mpId, fileExtension);
        return mediaUrl;
      }
      logger.error("Errow when uploading audio to Google Storage, error code: {}", mediaResponse);
      return mediaUrl;
    } catch (Exception e) {
      throw new TranscriptionServiceException("Error reading audio track", e);
    }
  }

  private JSONArray getTranscriptionResult(JSONObject jsonObj) {
    JSONObject responseObj = (JSONObject) jsonObj.get("response");
    JSONArray resultsArray = (JSONArray) responseObj.get("results");
    return resultsArray;
  }

  protected void deleteStorageFile(String mpId, String token) throws IOException {
    CloseableHttpClient httpClientDel = makeHttpClient();
    NibityTranscriptionServiceStorage storage = new NibityTranscriptionServiceStorage();
    storage.deleteGoogleStorageFile(httpClientDel, storageBucket, mpId + ".flac", token);
  }

  private void sendEmail(String subject, String body) {
    if (toEmailAddress == null) {
      logger.info("Skipping sending email notification. Message is {}.", body);
      return;
    }
    try {
      logger.debug("Sending e-mail notification to {}", toEmailAddress);
      smtpService.send(toEmailAddress, String.format("%s (%s)", subject, clusterName), body);
      logger.info("Sent e-mail notification to {}", toEmailAddress);
    } catch (Exception e) {
      logger.error(String.format("Could not send email: %s\n%s", subject, body), e);
    }
  }

  private String buildResultsFileName(String jobId) {
    return PathSupport.toSafeName(jobId + ".json");
  }

  public void setServiceRegistry(ServiceRegistry serviceRegistry) {
    this.serviceRegistry = serviceRegistry;
  }

  public void setSecurityService(SecurityService securityService) {
    this.securityService = securityService;
  }

  public void setUserDirectoryService(UserDirectoryService userDirectoryService) {
    this.userDirectoryService = userDirectoryService;
  }

  public void setOrganizationDirectoryService(OrganizationDirectoryService organizationDirectoryService) {
    this.organizationDirectoryService = organizationDirectoryService;
  }

  public void setSmtpService(SmtpService service) {
    this.smtpService = service;
  }

  public void setWorkspace(Workspace ws) {
    this.workspace = ws;
  }

  public void setWorkingFileRepository(WorkingFileRepository wfr) {
    this.wfr = wfr;
  }

  public void setDatabase(NibityTranscriptionDatabase service) {
    this.database = service;
  }

  public void setAssetManager(AssetManager service) {
    this.assetManager = service;
  }

  public void setWorkflowService(WorkflowService service) {
    this.workflowService = service;
  }

  @Override
  protected ServiceRegistry getServiceRegistry() {
    return serviceRegistry;
  }

  @Override
  protected SecurityService getSecurityService() {
    return securityService;
  }

  @Override
  protected UserDirectoryService getUserDirectoryService() {
    return userDirectoryService;
  }

  @Override
  protected OrganizationDirectoryService getOrganizationDirectoryService() {
    return organizationDirectoryService;
  }

  // Only used by unit tests!
  void setWfUtil(Workflows wfUtil) {
    this.wfUtil = wfUtil;
  }

  class WorkflowDispatcher implements Runnable {

    /**
     * {@inheritDoc}
     *
     * @see java.lang.Thread#run()
     */
    @Override
    public void run() {
      logger.debug("WorkflowDispatcher waking up...");

      try {
        // Find jobs that are in progress and jobs that had transcription complete
        List<NibityTranscriptionJobControl> jobs = database.findByStatus(NibityTranscriptionJobControl.Status.Progress.name(),
                NibityTranscriptionJobControl.Status.TranscriptionComplete.name());
        for (NibityTranscriptionJobControl j : jobs) {
          String mpId = j.getMediaPackageId();
          String jobId = j.getTranscriptionJobId();

          // If the job in progress, check if it should already have finished.
          if (NibityTranscriptionJobControl.Status.Progress.name().equals(j.getStatus())) {
            // If job should already have been completed, try to get the results. Consider a buffer factor so that we
            // don't try it too early.
            if (j.getDateCreated().getTime() + j.getTrackDuration() + completionCheckBuffer * 1000 < System
                    .currentTimeMillis()) {
              try {
                if (!getAndSaveJobResults(jobId)) {
                  // Job still running, not finished, so check if it should have finished more than N seconds ago
                  if (j.getDateCreated().getTime() + j.getTrackDuration()
                          + (completionCheckBuffer + maxProcessingSeconds) * 1000 < System.currentTimeMillis()) {
                    // Processing for too long, mark job as canceled and don't check anymore
                    database.updateJobControl(jobId, NibityTranscriptionJobControl.Status.Canceled.name());
                    // Delete file stored on Google storage
                    String token = getRefreshAccessToken();
                    deleteStorageFile(mpId, token);
                    // Send notification email
                    sendEmail("Transcription ERROR", String.format(
                            "Transcription job was in processing state for too long and was marked as canceled (media package %s, job id %s).",
                            mpId, jobId));
                  }
                  // else Job still running, not finished
                  continue;
                }
              } catch (TranscriptionServiceException e) {
                if (e.getCode() == 404) {
                  // Job not found there, update job state to canceled
                  database.updateJobControl(jobId, NibityTranscriptionJobControl.Status.Canceled.name());
                  // Send notification email
                  sendEmail("Transcription ERROR",
                          String.format("Transcription job was not found (media package %s, job id %s).", mpId, jobId));
                }
                continue; // Skip this one, exception was already logged
              } catch (IOException ex) {
                logger.error("Transcription job not found, error: {}", ex.toString());
              }
            } else {
              continue; // Not time to check yet
            }
          }

          // Jobs that get here have state TranscriptionCompleted
          try {
            DefaultOrganization defaultOrg = new DefaultOrganization();
            securityService.setOrganization(defaultOrg);
            securityService.setUser(SecurityUtil.createSystemUser(systemAccount, defaultOrg));

            // Find the episode
            final AQueryBuilder q = assetManager.createQuery();
            final AResult r = q.select(q.snapshot()).where(q.mediaPackageId(mpId).and(q.version().isLatest())).run();
            if (r.getSize() == 0) {
              // Media package not archived yet? Skip until next time.
              logger.warn("Media package {} has not been archived yet. Skipped.", mpId);
              continue;
            }

            String org = Enrichments.enrich(r).getSnapshots().head2().getOrganizationId();
            Organization organization = organizationDirectoryService.getOrganization(org);
            if (organization == null) {
              logger.warn("Media package {} has an unknown organization {}. Skipped.", mpId, org);
              continue;
            }
            securityService.setOrganization(organization);

            // Build workflow
            Map<String, String> params = new HashMap<String, String>();
            params.put("transcriptionJobId", jobId);
            WorkflowDefinition wfDef = workflowService.getWorkflowDefinitionById(workflowDefinitionId);

            // Apply workflow
            // wfUtil is only used by unit tests
            Workflows workflows = wfUtil != null ? wfUtil : new Workflows(assetManager, workspace, workflowService);
            Set<String> mpIds = new HashSet<String>();
            mpIds.add(mpId);
            List<WorkflowInstance> wfList = workflows
                    .applyWorkflowToLatestVersion(mpIds, ConfiguredWorkflow.workflow(wfDef, params)).toList();
            String wfId = wfList.size() > 0 ? Long.toString(wfList.get(0).getId()) : "Unknown";

            // Update state in the database
            database.updateJobControl(jobId, NibityTranscriptionJobControl.Status.Closed.name());
            logger.info("Attach transcription workflow {} scheduled for mp {}, google speech job {}", new String[]{wfId,
              mpId, jobId});
          } catch (Exception e) {
            logger.warn("Attach transcription workflow could NOT be scheduled for mp {}, nibity job {}, {}: {}",
                    new String[]{mpId, jobId, e.getClass().getName(), e.getMessage()});
          }
        }
      } catch (NibityTranscriptionDatabaseException e) {
        logger.warn("Could not read transcription job control database: {}", e.getMessage());
      }
    }
  }

  class ResultsFileCleanup implements Runnable {

    @Override
    public void run() {
      logger.info("ResultsFileCleanup waking up...");
      try {
        // Cleans up results files older than CLEANUP_RESULT_FILES_DAYS days
        wfr.cleanupOldFilesFromCollection(TRANSCRIPT_COLLECTION, cleanupResultDays);
      } catch (IOException e) {
        logger.warn("Could not cleanup old transcript results files", e);
      }
    }
  }

}
