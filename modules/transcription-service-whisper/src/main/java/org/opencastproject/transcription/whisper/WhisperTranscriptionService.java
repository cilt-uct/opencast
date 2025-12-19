/*
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

package org.opencastproject.transcription.whisper;

import org.opencastproject.assetmanager.api.AssetManager;
import org.opencastproject.assetmanager.api.fn.Enrichments;
import org.opencastproject.assetmanager.api.query.AQueryBuilder;
import org.opencastproject.assetmanager.api.query.AResult;
import org.opencastproject.assetmanager.util.Workflows;
import org.opencastproject.job.api.AbstractJobProducer;
import org.opencastproject.job.api.Job;
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
import org.opencastproject.transcription.persistence.TranscriptionDatabase;
import org.opencastproject.transcription.persistence.TranscriptionDatabaseException;
import org.opencastproject.transcription.persistence.TranscriptionJobControl;
import org.opencastproject.transcription.persistence.TranscriptionProviderControl;
import org.opencastproject.util.OsgiUtil;
import org.opencastproject.util.data.Option;
import org.opencastproject.workflow.api.ConfiguredWorkflow;
import org.opencastproject.workflow.api.WorkflowDefinition;
import org.opencastproject.workflow.api.WorkflowInstance;
import org.opencastproject.workflow.api.WorkflowService;
import org.opencastproject.workingfilerepository.api.WorkingFileRepository;
import org.opencastproject.workspace.api.Workspace;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component(
        immediate = true,
        service = { TranscriptionService.class, WhisperTranscriptionService.class },
        property = {
                "service.description=Whisper Transcription Service",
                "provider=whisper"
        }
)

public class WhisperTranscriptionService extends AbstractJobProducer implements TranscriptionService {

  private static final Logger logger = LoggerFactory.getLogger(WhisperTranscriptionService.class);

  private static final String JOB_TYPE = "org.opencastproject.transcription.whisper";

  public static final String SUBMISSION_COLLECTION = "whisper-submission";
  private static final String TRANSCRIPT_COLLECTION = "whisper-transcripts";

  private static final int CONNECTION_TIMEOUT = 60000; // ms, 1 minute
  private static final int SOCKET_TIMEOUT = 60000; // ms, 1 minute


  private static final String BASE_URL = "https://api.openai.com/v1";
  private static final String STATUS_OPEN = "OPEN";
  private static final String STATUS_DONE = "DONE";
  private static final String STATUS_ERROR = "ERROR";

  private static final String ERROR_NO_SPEECH = "No speech found";

  private static final String PROVIDER = "whisper";

  private AssetManager assetManager;
  private OrganizationDirectoryService organizationDirectoryService;
  private ScheduledExecutorService scheduledExecutor;
  private SecurityService securityService;
  private ServiceRegistry serviceRegistry;
  private TranscriptionDatabase database;
  private UserDirectoryService userDirectoryService;
  private WorkflowService workflowService;
  private WorkingFileRepository wfr;
  private Workspace workspace;

  // Only used by unit tests
  private Workflows wfUtil;

  private enum Operation {
    StartTranscription
  }

  // service configuration keys
  private static final String ENABLED_CONFIG = "enabled";
  private static final String LANGUAGE = "language";
  private static final String WORKFLOW_CONFIG = "workflow";
  private static final String DISPATCH_WORKFLOW_INTERVAL_CONFIG = "workflow.dispatch.interval";
  private static final String CLEANUP_RESULTS_DAYS_CONFIG = "cleanup.results.days";
  private static final String API_CLIENT_KEY = "whisper.client.api.key";
  private static final String API_CLIENT_SECRET = "whisper.client.api.secret";

  /**
   * Service configuration values
   */
  private boolean enabled = true;
  private String apiClientId;
  private String apiClientKey;
  private String apiClientSecret;
  private String language = "en";
  private String workflowDefinitionId = "whisper-attach-transcripts";
  private long workflowDispatchIntervalSeconds = 60;
  private long maxProcessingSeconds = 48 * 60 * 60;
  private int cleanupResultDays = 7;
  private String model = "gpt-4o-transcribe";  // Opts gpt-4o-mini-transcribe, whisper-1, gpt-4o-transcribe-diarize
  private String responseFormat = "vtt";
  private String prompt = "";
  private String systemAccount;
  private String serverUrl;
  private String callbackUrl;
  private String logging = "0";

  public WhisperTranscriptionService() {
    super(JOB_TYPE);
  }

  @Override
  @Activate
  public void activate(ComponentContext cc) {
    logger.info("Activating Whisper API Transcription Service!");

    Option<Boolean> enabledOpt = OsgiUtil.getOptCfgAsBoolean(cc.getProperties(), ENABLED_CONFIG);
    if (enabledOpt.isSome()) {
      enabled = enabledOpt.get();
    }

    if (!enabled) {
      logger.info("Whisper Transcription Service disabled."
              + " If you want to enable it, please update the service configuration.");
      return;
    }

    apiClientKey = OsgiUtil.getComponentContextProperty(cc, API_CLIENT_KEY);
    apiClientSecret = OsgiUtil.getComponentContextProperty(cc, API_CLIENT_SECRET);
    logger.info("Whisper Transcription Service enabled with client id {}", apiClientId);

    Option<String> languageOpt = OsgiUtil.getOptCfg(cc.getProperties(), LANGUAGE);
    if (languageOpt.isSome()) {
      language = languageOpt.get();
      logger.info("Language is set to '{}'.", language);
    } else {
      logger.info("Default language '{}' will be used.", language);
    }

    Option<String> wfOpt = OsgiUtil.getOptCfg(cc.getProperties(), WORKFLOW_CONFIG);
    if (wfOpt.isSome()) {
      workflowDefinitionId = wfOpt.get();
      logger.info("Workflow is set to '{}'.", workflowDefinitionId);
    } else {
      logger.info("Default workflow '{}' will be used.", workflowDefinitionId);
    }

    Option<String> intervalOpt = OsgiUtil.getOptCfg(cc.getProperties(), DISPATCH_WORKFLOW_INTERVAL_CONFIG);
    if (intervalOpt.isSome()) {
      try {
        workflowDispatchIntervalSeconds = Long.parseLong(intervalOpt.get());
      } catch (NumberFormatException e) {
        logger.warn("Configured '{}' is invalid. Using default.", DISPATCH_WORKFLOW_INTERVAL_CONFIG);
      }
    }
    logger.info("Workflow dispatch interval is {} seconds.", workflowDispatchIntervalSeconds);


    Option<String> cleanupOpt = OsgiUtil.getOptCfg(cc.getProperties(), CLEANUP_RESULTS_DAYS_CONFIG);
    if (cleanupOpt.isSome()) {
      try {
        cleanupResultDays = Integer.parseInt(cleanupOpt.get());
      } catch (NumberFormatException e) {
        logger.warn("Configured '{}' is invalid. Using default.", CLEANUP_RESULTS_DAYS_CONFIG);
      }
    }
    logger.info("Cleanup result files after {} days.", cleanupResultDays);

    serverUrl = OsgiUtil.getContextProperty(cc, OpencastConstants.SERVER_URL_PROPERTY);
    systemAccount = OsgiUtil.getContextProperty(cc, OpencastConstants.DIGEST_USER_PROPERTY);

    scheduledExecutor = Executors.newScheduledThreadPool(2);

    scheduledExecutor.scheduleWithFixedDelay(new WorkflowDispatcher(), 120, workflowDispatchIntervalSeconds,
            TimeUnit.SECONDS);

    scheduledExecutor.scheduleWithFixedDelay(new ResultsFileCleanup(), 1, 1, TimeUnit.DAYS);

    logger.info("Activated.");
  }

  @Deactivate
  public void deactivate() {
    if (scheduledExecutor != null) {
      scheduledExecutor.shutdown();
    }
  }

  @Override
  public Job startTranscription(String mpId, Track track) throws TranscriptionServiceException {
    throw new UnsupportedOperationException("Not supported.");
  }

  @Override
  public Job startTranscription(String mpId, Track track, String... args) throws TranscriptionServiceException {
    if (!enabled) {
      throw new TranscriptionServiceException("Whisper Transcription Service disabled."
              + " If you want to enable it, please update the service configuration.");
    }

    String language = null;

    if (language == null) {
      if (args.length > 0 && StringUtils.isNotBlank(args[0])) {
        language = args[0];
      } else {
        language = getLanguage();
      }
    }

    try {
      return serviceRegistry.createJob(JOB_TYPE, Operation.StartTranscription.name(), Arrays.asList(
          mpId, MediaPackageElementParser.getAsXml(track), language, model, responseFormat));
    } catch (ServiceRegistryException e) {
      throw new TranscriptionServiceException("Unable to create a job", e);
    } catch (MediaPackageException e) {
      throw new TranscriptionServiceException("Invalid track '" + track.toString() + "'", e);
    }
  }

  @Override
  public void transcriptionDone(String mpId, Object results) { }

  private void transcriptionDone(String mpId, String jobId) {
    try {
      logger.info("Transcription done for mpId '{}'.", mpId);
      database.updateJobControl(jobId, TranscriptionJobControl.Status.TranscriptionComplete.name());
    } catch (TranscriptionDatabaseException e) {
      logger.warn("Transcription results file were saved but state in db not updated for mpId '{}': ", mpId, e);
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
      database.updateJobControl(jobId, TranscriptionJobControl.Status.Error.name());
      TranscriptionJobControl jobControl = database.findByJob(jobId);
      logger.warn(String.format("Error received for media package %s, job id %s",
              jobControl.getMediaPackageId(), jobId));
      // Send notification email
    } catch (TranscriptionDatabaseException e) {
      logger.warn("Transcription error. State in db could not be updated to error for mpId {}, jobId {}", mpId, jobId);
      throw new TranscriptionServiceException("Could not update transcription job control db", e);
    }
  }

  @Override
  public String getJobType() {
    return JOB_TYPE;
  }

  @Override
  public String getLanguage() {
    return language;
  }

  // Called by workflow
  @Override
  protected String process(Job job) throws Exception {
    Operation op = null;
    String operation = job.getOperation();
    List<String> arguments = job.getArguments();
    String result = "";
    op = Operation.valueOf(operation);

    logger.warn("WHISPER PROCESS START: operation={}, args={}", operation, arguments);

    switch (op) {
      case StartTranscription:
        String mpId = arguments.get(0);
        Track track = (Track) MediaPackageElementParser.getFromXml(arguments.get(1));
        String languageCode = arguments.get(2);
        String model = arguments.get(3);
        String responseFormat = arguments.get(4);

        createRecognitionsJob(mpId, track, languageCode, model, responseFormat);

        result = "Transcription completed synchronously";
        break;
      default:
        throw new IllegalStateException("Don't know how to handle operation '" + operation + "'");
    }
    return result;
  }

  void createRecognitionsJob(String mpId, Track track, String languageCode, String model, String responseFormat)
          throws TranscriptionServiceException {

    CloseableHttpClient httpClient = makeHttpClient(3 * 3600 * 1000);
    CloseableHttpResponse response = null;

    String submitUrl = BASE_URL + "/audio/transcriptions";

    logger.warn("WHISPER HTTP CALL → model={}, language={}, url={}", model, languageCode, submitUrl);

    try {
      // Prepare audio file body
      FileBody fileBody = new FileBody(workspace.get(track.getURI()), ContentType.DEFAULT_BINARY);

      MultipartEntityBuilder builder = MultipartEntityBuilder.create();
      builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);

      // Required fields for OpenAI
      builder.addPart("file", fileBody);
      builder.addTextBody("model", model);

      // Optional fields if you want them
      if (StringUtils.isNotBlank(languageCode)) {
        builder.addTextBody("language", languageCode);
      }
      builder.addTextBody("response_format", responseFormat);

      HttpEntity multipartEntity = builder.build();

      HttpPost httpPost = new HttpPost(submitUrl);
      httpPost.setEntity(multipartEntity);

      // REQUIRED: OpenAI API key must be sent as a header
      httpPost.setHeader("Authorization", "Bearer " + apiClientKey);

      // Execute request
      response = httpClient.execute(httpPost);
      int code = response.getStatusLine().getStatusCode();
      String body = EntityUtils.toString(response.getEntity());

      // logger.debug("Whisper response ({}): {}", code, body);
      // HttpEntity entity = response.getEntity();

      // String jsonString = EntityUtils.toString(entity);
      // JSONParser jsonParser = new JSONParser();
      // JSONObject jsonObject = (JSONObject) jsonParser.parse(jsonString);

      logger.debug("Submitting new OpenAI Whisper transcription job: {}" + System.lineSeparator()
          + "Response: {}", submitUrl, body);

      // OpenAI returns the transcription result immediately – there is NO jobId
      // // So a jobId must be generated (same approach as other providers)
      // JSONObject result = (JSONObject) jsonObject.get("jobStatus");
      // String jobId = (String) result.get("jobId");

      switch (code) {
        case HttpStatus.SC_OK: // 200
          // Generate jobId yourself
          String jobId = UUID.randomUUID().toString();
          logger.info("mp {} has been transcribed by OpenAI Whisper (jobId {}).", mpId, jobId);

          // Store job as "TranscriptionComplete" immediately
          database.storeJobControl(
              mpId,
              track.getIdentifier(),
              jobId,
              TranscriptionJobControl.Status.TranscriptionComplete.name(),
              track.getDuration() == null ? 0 : track.getDuration().longValue(),
              new Date(),
              PROVIDER
          );

          // Save the returned VTT into the transcript collection
          // String vttContent = jsonString; // Because OpenAI returns plain text for VTT
          // InputStream in = new ByteArrayInputStream(vttContent.getBytes(StandardCharsets.UTF_8));
          // workspace.putInCollection(TRANSCRIPT_COLLECTION, jobId + ".vtt", in);

          // EntityUtils.consume(entity);
          return;

        default:
          JSONObject errObj = (JSONObject) new JSONParser().parse(body);
          JSONObject err = (JSONObject) errObj.get("error");
          String message = err != null ? (String) err.get("message") : "Unknown error";
          String msg = String.format("OpenAI API returned %s: %s", code, message);
          logger.warn(msg);
          throw new TranscriptionServiceException(msg);
      }

    } catch (Exception e) {
      logger.warn("Exception when calling OpenAI Whisper endpoint", e);
      throw new TranscriptionServiceException("Exception when calling the Whisper endpoint", e);

    } finally {
      try {
        httpClient.close();
        if (response != null) {
          response.close();
        }
      } catch (IOException e) {
        // ignore
      }
    }
  }

  /**
   * Creates a closable http client with default configuration.
   *
   * @return closable http client
   */
  protected CloseableHttpClient makeHttpClient() {
    return makeHttpClient(SOCKET_TIMEOUT);
  }

  /**
   * Creates a closable http client.
   *
   * @param socketTimeout http socket timeout value in milliseconds
   */
  protected CloseableHttpClient makeHttpClient(int socketTimeout) {
    RequestConfig reqConfig = RequestConfig.custom()
        .setConnectTimeout(WhisperTranscriptionService.CONNECTION_TIMEOUT)
        .setSocketTimeout(socketTimeout)
        .setConnectionRequestTimeout(WhisperTranscriptionService.CONNECTION_TIMEOUT)
        .build();
    CloseableHttpClient httpClient = HttpClientBuilder.create()
        .useSystemProperties()
        .setDefaultRequestConfig(reqConfig)
        .build();
    return httpClient;
  }

  @Reference
  public void setServiceRegistry(ServiceRegistry serviceRegistry) {
    this.serviceRegistry = serviceRegistry;
  }

  @Reference
  public void setSecurityService(SecurityService securityService) {
    this.securityService = securityService;
  }

  @Reference
  public void setUserDirectoryService(UserDirectoryService userDirectoryService) {
    this.userDirectoryService = userDirectoryService;
  }

  @Reference
  public void setOrganizationDirectoryService(OrganizationDirectoryService organizationDirectoryService) {
    this.organizationDirectoryService = organizationDirectoryService;
  }

  @Reference
  public void setWorkspace(Workspace ws) {
    this.workspace = ws;
  }

  @Reference
  public void setWorkingFileRepository(WorkingFileRepository wfr) {
    this.wfr = wfr;
  }

  @Reference
  public void setDatabase(TranscriptionDatabase service) {
    this.database = service;
  }

  @Reference
  public void setAssetManager(AssetManager service) {
    this.assetManager = service;
  }

  @Reference
  public void setWorkflowService(WorkflowService service) {
    this.workflowService = service;
  }

  @Override
  public Map<String, Object> getReturnValues(String mpId, String jobId) throws TranscriptionServiceException {
    throw new TranscriptionServiceException("Method not implemented");
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

  @Override
  public MediaPackageElement getGeneratedTranscription(String mpId, String jobId, MediaPackageElement.Type type)
          throws TranscriptionServiceException {

    try {
      // If jobId is not provided, find the latest completed transcription for this media package
      if (jobId == null || "null".equals(jobId)) {
        jobId = null;
        for (TranscriptionJobControl jc : database.findByMediaPackage(mpId)) {
          if (TranscriptionJobControl.Status.TranscriptionComplete.name().equals(jc.getStatus())) {
            jobId = jc.getTranscriptionJobId();
          }
        }
      }

      if (jobId == null) {
        throw new TranscriptionServiceException(
          "No completed transcription job found for media package " + mpId);
      }

      // Build the URI for the stored VTT file
      URI uri = workspace.getCollectionURI(TRANSCRIPT_COLLECTION, jobId + ".vtt");

      try {
        workspace.get(uri);
        logger.info("Found captions at URI: {}", uri);
      } catch (Exception e) {
        throw new TranscriptionServiceException(
            "Transcription file not found for jobId " + jobId, e);
      }

      // Build the MediaPackageElement to return
      MediaPackageElementBuilder builder = MediaPackageElementBuilderFactory.newInstance().newElementBuilder();
      return builder.elementFromURI(uri, type, new MediaPackageElementFlavor("captions", "vtt"));

    } catch (TranscriptionDatabaseException e) {
      throw new TranscriptionServiceException("Error retrieving transcription for mp " + mpId, e);
    }
  }

   /**
   * Add the media file to local storage.
   * @return the filename in the collection
   */
  protected String addMediaFileToLocalStorage(String mpId, Track track)
          throws TranscriptionServiceException, IOException {
    try {
      String filename = mpId + "_media." + FilenameUtils.getExtension(track.getURI().toString());

      // Seems unnecessary to have to read & write this rather than just hardlink it
      wfr.putInCollection(SUBMISSION_COLLECTION, filename, workspace.read(track.getURI()));

      return filename;
    } catch (Exception e) {
      throw new TranscriptionServiceException("Error adding file to collection", e);
    }
  }

  // Called when a transcription job has been submitted
  protected void deleteStorageFile(String filename) throws IOException {
    try {
      wfr.deleteFromCollection(SUBMISSION_COLLECTION, filename, false);
    } catch (IOException e) {
      logger.warn("Unable to remove submission file {} from collection {}", filename, SUBMISSION_COLLECTION);
    }
  }

  private String buildResultsFileName(String jobId, String extension) {
    return workspace.toSafeName(jobId + "." + extension);
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
        long providerId;
        TranscriptionProviderControl providerInfo = database.findIdByProvider(PROVIDER);
        if (providerInfo != null) {
          providerId = providerInfo.getId();
        } else {
          logger.debug("No jobs yet for provider {}", PROVIDER);
          return;
        }

        List<TranscriptionJobControl> jobs = database.findByStatus(TranscriptionJobControl.Status.InProgress.name(),
                TranscriptionJobControl.Status.TranscriptionComplete.name());

        for (TranscriptionJobControl j : jobs) {

          // Don't process jobs for other services
          if (j.getProviderId() != providerId) {
            continue;
          }

          String mpId = j.getMediaPackageId();
          String jobId = j.getTranscriptionJobId();

          // If the job in progress, check if it should already have finished.
          if (TranscriptionJobControl.Status.InProgress.name().equals(j.getStatus())) {
            // Update state in the database
            database.updateJobControl(jobId, TranscriptionJobControl.Status.TranscriptionComplete.name());
            logger.info("Transcription complete for mp {}, transcription service job {}",
                new String[]{ mpId, jobId});
          } else {
            continue;
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

            String org = Enrichments.enrich(r).getSnapshots().stream().findFirst().get().getOrganizationId();
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
            Workflows workflows = wfUtil != null ? wfUtil : new Workflows(assetManager, workflowService);
            Set<String> mpIds = new HashSet<String>();
            mpIds.add(mpId);
            List<WorkflowInstance> wfList = workflows
                    .applyWorkflowToLatestVersion(mpIds, ConfiguredWorkflow.workflow(wfDef, params)).toList();
            String wfId = wfList.size() > 0 ? Long.toString(wfList.get(0).getId()) : "Unknown";

            // Update state in the database
            database.updateJobControl(jobId, TranscriptionJobControl.Status.Closed.name());
            logger.info("Attach transcription workflow {} scheduled for mp {}, transcription service job {}",
                new String[]{wfId, mpId, jobId});
          } catch (Exception e) {
            logger.warn("Attach transcription workflow could NOT be scheduled for mp {}, whisper job {}, {}: {}",
                    new String[]{mpId, jobId, e.getClass().getName(), e.getMessage()});
          }
        }
      } catch (TranscriptionDatabaseException e) {
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
        wfr.cleanupOldFilesFromCollection(SUBMISSION_COLLECTION, cleanupResultDays);
      } catch (IOException e) {
        logger.warn("Could not cleanup old submission and transcript results files", e);
      }
    }
  }
}
