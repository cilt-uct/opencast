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


// import java.util.concurrent.TimeUnit;
// import org.apache.commons.io.IOUtils;


package org.opencastproject.transcription.whisper;

import org.opencastproject.assetmanager.api.AssetManager;
import org.opencastproject.assetmanager.api.Snapshot;
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
import org.opencastproject.util.MimeType;
import org.opencastproject.util.NotFoundException;
import org.opencastproject.util.OsgiUtil;
import org.opencastproject.workflow.api.ConfiguredWorkflow;
import org.opencastproject.workflow.api.WorkflowDefinition;
import org.opencastproject.workflow.api.WorkflowInstance;
import org.opencastproject.workflow.api.WorkflowService;
import org.opencastproject.workingfilerepository.api.WorkingFileRepository;
import org.opencastproject.workspace.api.Workspace;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

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
  public static final String WHISPER_API_KEY = "whisper.client.api.key";
  private static final String WHISPER_URL = "https://api.openai.com/v1/audio/transcriptions";
  private static final String SUBMISSION_PATH = "/transcripts/whisper/submission/";
  public static final String WORKFLOW_CONFIG = "workflow";
  public static final String DISPATCH_WORKFLOW_INTERVAL_CONFIG = "workflow.dispatch.interval";
  public static final String MAX_PROCESSING_TIME_CONFIG = "max.overdue.time";
  public static final String NOTIFICATION_EMAIL_CONFIG = "notification.email";
  public static final String CLEANUP_RESULTS_DAYS_CONFIG = "cleanup.results.days";
  public static final String CLEANUP_SUBMISSION = "cleanup.submission";

  private static final String DEFAULT_WF_DEF = "whisper-attach-transcripts";
  private static final long DEFAULT_DISPATCH_INTERVAL = 60; // in seconds, default is 1 minute
  private static final long DEFAULT_MAX_PROCESSING_TIME = 48 * 60 * 60; // in seconds, default is 2 days.

  // Cleans up results files that are older than 7 days
  private static final int DEFAULT_CLEANUP_RESULTS_DAYS = 7;
  private static final String WHISPER_LANGUAGE = "en-US";
  private static final int TIMEOUT = 60000;
  private final int segmentSeconds = 600; // 10 minutes
  private final int parallelism = 3;

    // Cluster name
  private String clusterName = "";

  private ServiceRegistry serviceRegistry;
  private SecurityService securityService;
  private Workspace workspace;
  private AssetManager assetManager;
  private UserDirectoryService userDirectoryService;
  private OrganizationDirectoryService organizationDirectoryService;
  private WorkflowService workflowService;
  private WorkingFileRepository wfr;

  private boolean enabled = true;
  private String apiKey;
  private String language = "en";
  private String workflowDefinitionId = DEFAULT_WF_DEF;
  private long workflowDispatchInterval = DEFAULT_DISPATCH_INTERVAL;
  private long maxProcessingSeconds = DEFAULT_MAX_PROCESSING_TIME;
  private String toEmailAddress;
  private int cleanupResultDays = DEFAULT_CLEANUP_RESULTS_DAYS;
  private boolean cleanupSubmission = true; // Remove submissions immediately
  private String systemAccount;
  private String serverUrl;
  private ScheduledExecutorService scheduledExecutor = null;

  // Only used by unit tests!
  private Workflows wfUtil;

  private enum Operation {
    StartTranscription
  }

  public WhisperTranscriptionService() {
    super(JOB_TYPE);
  }

  @Activate
  public void activate(ComponentContext cc) {
    enabled = OsgiUtil.getOptCfgAsBoolean(cc.getProperties(), "enabled").orElse(false);
    apiKey = OsgiUtil.getOptCfg(cc.getProperties(), "whisper.client.api.key").orElse(null);
    language = OsgiUtil.getOptCfg(cc.getProperties(), "whisper.language").orElse("en");

    // Cleanup submissions
    Optional<Boolean> cleanupOpt = OsgiUtil.getOptCfgAsBoolean(cc.getProperties(), CLEANUP_SUBMISSION);
    if (cleanupOpt.orElse(false)) {
      cleanupSubmission = cleanupOpt.get();
    }

    if (cleanupSubmission) {
      logger.info("Temporary media files will be removed immediately after submission");
    } else {
      logger.info("Temporary submission media files will NOT be removed immediately");
    }

    // Language model to be used
    Optional<String> languageOpt = OsgiUtil.getOptCfg(cc.getProperties(), WHISPER_LANGUAGE);
    if (languageOpt.isPresent()) {
      language = languageOpt.get();
      logger.info("Language used is {}", language);
    } else {
      logger.info("Default language will be used");
    }

    // Workflow to execute when getting callback (optional, with default)
    Optional<String> wfOpt = OsgiUtil.getOptCfg(cc.getProperties(), WORKFLOW_CONFIG);
    if (wfOpt.isPresent()) {
      workflowDefinitionId = wfOpt.get();
    }
    logger.info("Workflow definition is {}", workflowDefinitionId);

    // Interval to check for completed transcription jobs and start workflows to attach transcripts
    Optional<String> intervalOpt = OsgiUtil.getOptCfg(cc.getProperties(), DISPATCH_WORKFLOW_INTERVAL_CONFIG);
    if (intervalOpt.isPresent()) {
      try {
        workflowDispatchInterval = Long.parseLong(intervalOpt.get());
      } catch (NumberFormatException e) {
        // Use default
      }
    }
    logger.info("Workflow dispatch interval is {} seconds", workflowDispatchInterval);

    // How long to wait after a transcription is supposed to finish before marking the job as canceled in the db
    Optional<String> maxProcessingOpt = OsgiUtil.getOptCfg(cc.getProperties(), MAX_PROCESSING_TIME_CONFIG);
    if (maxProcessingOpt.isPresent()) {
      try {
        maxProcessingSeconds = Long.parseLong(maxProcessingOpt.get());
      } catch (NumberFormatException e) {
        // Use default
      }
    }
    logger.info("Maximum time a job is checked after it should have ended is {} seconds", maxProcessingSeconds);

    // How long to keep result files in the working file repository
    Optional<String> cleaupOpt = OsgiUtil.getOptCfg(cc.getProperties(), CLEANUP_RESULTS_DAYS_CONFIG);
    if (cleaupOpt.isPresent()) {
      try {
        cleanupResultDays = Integer.parseInt(cleaupOpt.get());
      } catch (NumberFormatException e) {
        // Use default
      }
    }
    logger.info("Cleanup result files after {} days", cleanupResultDays);

    serverUrl = OsgiUtil.getContextProperty(cc, OpencastConstants.SERVER_URL_PROPERTY);
    systemAccount = OsgiUtil.getContextProperty(cc, OpencastConstants.DIGEST_USER_PROPERTY);

    // New execute service
    scheduledExecutor = Executors.newScheduledThreadPool(2);

    // // Schedule the workflow dispatching, starting in 2 minutes
    // scheduledExecutor.scheduleWithFixedDelay(new WorkflowDispatcher(), 120, workflowDispatchInterval,
    //         TimeUnit.SECONDS);

    // // Schedule the cleanup of old results jobs from the collection in the WFR once a day
    // scheduledExecutor.scheduleWithFixedDelay(new ResultsFileCleanup(), 1, 1, TimeUnit.DAYS);

    // Notification email passed in this service configuration?
    Optional<String> optTo = OsgiUtil.getOptCfg(cc.getProperties(), NOTIFICATION_EMAIL_CONFIG);
    if (optTo.isPresent()) {
      toEmailAddress = optTo.get();
    } else {
      // Use admin email informed in custom.properties
      optTo = OsgiUtil.getOptContextProperty(cc, OpencastConstants.ADMIN_EMAIL_PROPERTY);
      if (optTo.isPresent()) {
        toEmailAddress = optTo.get();
      }
    }
    if (toEmailAddress != null) {
      logger.info("Notification email set to {}", toEmailAddress);
    } else {
      logger.warn("Email notification disabled");
    }

    Optional<String> optCluster = OsgiUtil.getOptContextProperty(cc, OpencastConstants.ENVIRONMENT_NAME_PROPERTY);
    if (optCluster.isPresent()) {
      clusterName = optCluster.get();
      logger.info("Environment name is {}", clusterName);
    }

    logger.info("Activated!");
  }

  public void deactivate(ComponentContext cc) {
    if (scheduledExecutor != null) {
      scheduledExecutor.shutdown();
    }
  }

  // Called by Submission WOH
  @Override
  public Job startTranscription(String mpId, Track track) throws TranscriptionServiceException {
    logger.info("Inside startTranscription for mpId: {} and track: {}", mpId, track);
    if (!enabled) {
      throw new TranscriptionServiceException(
              "This service is disabled. If you want to enable it, please update the service configuration.");
    }

    try {
      logger.info("job type: {}, operation name: {}, language: {}", JOB_TYPE,
              Operation.StartTranscription.name(), language);

      String trackXml = MediaPackageElementParser.getAsXml(track);
      logger.info("Track XML length: {}", trackXml.length());

      Job job =  serviceRegistry.createJob(
          JOB_TYPE,
          Operation.StartTranscription.name(),
          Arrays.asList(mpId, trackXml, language)
      );

      logger.info("job {}", job);

      try {
        process(job);
      } catch (Exception e) {
        throw new TranscriptionServiceException("Failed to process the job", e);
      }

      return job;
    } catch (ServiceRegistryException e) {
      logger.info("Unable to create a job: {}", e);
      throw new TranscriptionServiceException("Unable to create a job", e);
    } catch (MediaPackageException e) {
      logger.info("Invalid track: {},  error: {}", track.toString(), e);
      throw new TranscriptionServiceException("Invalid track " + track.toString(), e);
    }
  }

  @Override
  public Job startTranscription(String mpId, Track track, String... args)
          throws TranscriptionServiceException {
    return startTranscription(mpId, track);
  }

  @Override
  protected String process(Job job) throws Exception {
    logger.info("Whisper JobProducer process() ENTERED");
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
        logger.info("mpId: {}", mpId);
        logger.info("track: {}", track);
        logger.info("language: {}", languageCode);
        // File audioFile = createRecognitionsJob(mpId, track, languageCode);
        createRecognitionsJob(mpId, track, languageCode);
        break;
      default:
        throw new IllegalStateException("Don't know how to handle operation '" + operation + "'");
    }

    return result;
  }

  void createRecognitionsJob(String mpId, Track track, String languageCode)
          throws TranscriptionServiceException, IOException {

    File mediaFile ;
    try {
      mediaFile  = addMediaFileToLocalStorage(mpId, track);
    } catch (Exception e) {
      throw new TranscriptionServiceException("Failed to store media file to local storage", e);
    }

    String mediaUrl = serverUrl + SUBMISSION_PATH + mediaFile.getName();
    // File mediaFile = new File(SUBMISSION_COLLECTION, filename);

    logger.info("Media URL in intermediate storage: {}", mediaUrl);

    logger.info("Preparing to Submit media file {} to Whisper", mediaFile.getAbsolutePath());

    try {
      URI transcriptUri = transcribeAndSave(mediaFile, mpId);
      return;
    } catch (Exception e) {
      throw new TranscriptionServiceException("Whisper transcription failed", e);
    }
  }

  private File addMediaFileToLocalStorage(String mpId, Track track) throws Exception {
    String extension = FilenameUtils.getExtension(track.getURI().toString());
    String filename = mpId + "." + extension;

    logger.info("File name for submission collection: {}", filename);

    try {
      wfr.putInCollection(SUBMISSION_COLLECTION, filename, workspace.read(track.getURI()));

      InputStream in = wfr.getFromCollection(SUBMISSION_COLLECTION, filename);

      // create a temp file for ffmpeg
      File tempFile = File.createTempFile("whisper-input-", "." + extension);
      try (OutputStream out = new FileOutputStream(tempFile)) {
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) != -1) {
          out.write(buffer, 0, len);
        }
      }
      in.close();

      logger.info("Local file ready at: {}", tempFile.getAbsolutePath());
      return tempFile;
    } catch (Exception e) {
      throw new TranscriptionServiceException("Error adding file to collection", e);
    }
  }

  public URI transcribeAndSave(File inputMedia, String mpId) throws Exception {
    logger.info("Preparing to transcribe media: {}", inputMedia);

    // Convert to mono WAV
    File wav = convertToMonoWav(inputMedia);

    // Split into chunks
    List<File> chunks = splitIntoChunks(wav, segmentSeconds);
    logger.info("Created {} chunks for transcription", chunks.size());

    // Executor for parallel transcription
    ExecutorService executor = Executors.newFixedThreadPool(parallelism);
    List<Future<ChunkResult>> futures = new ArrayList<>();

    for (int i = 0; i < chunks.size(); i++) {
      final int index = i;
      final File chunk = chunks.get(i);
      logger.info("Chunk file {} exists={}, size={}", chunk.getAbsolutePath(), chunk.exists(), chunk.length());

      // Submit transcription task
      futures.add(executor.submit(() -> {
        logger.info("Submitting chunk {} to Whisper", index);

        // Get chunk duration
        double duration = getAudioDurationSeconds(chunk);

        // Transcribe chunk
        String vtt = transcribeChunkWithRetry(chunk);
        logger.info("Chunk {} transcription complete (duration={}s)", index, duration);

        return new ChunkResult(index, vtt, duration);
      }));
    }

    executor.shutdown();

    // Collect results in order
    Map<Integer, ChunkResult> orderedResults = new TreeMap<>();
    for (Future<ChunkResult> future : futures) {
      ChunkResult result = future.get();
      orderedResults.put(result.index, result);
    }

    // Shift timestamps and merge VTTs with cumulative offsets
    List<String> shifted = new ArrayList<>();
    double cumulativeOffset = 0.0;

    for (ChunkResult result : orderedResults.values()) {
      shifted.add(shiftVttTimestamps(result.vtt, cumulativeOffset));
      cumulativeOffset += result.durationSeconds;
    }

    String mergedVtt = mergeVtts(shifted);

    // Clean up temporary files
    // cleanup(wav, chunks);

    // Save merged VTT to Working File Repository
    URI transcriptUri = saveTranscript(mpId, mergedVtt);
    logger.info("Merged VTT saved to workspace at {}", transcriptUri);
    // workflowInstance.setConfiguration("whisper.vtt.uri", transcriptUri.toString());

    return transcriptUri;
  }

  private URI saveTranscript(String mpId, String vtt) throws Exception {
    File tmp = File.createTempFile("whisper-", ".vtt");
    FileUtils.writeStringToFile(tmp, vtt, StandardCharsets.UTF_8);

    URI uri;
    try (InputStream is = new FileInputStream(tmp)) {
      uri = workspace.put(securityService.getOrganization().getId(),
            mpId,
            "captions.vtt",
            is);
    }

    return uri;
  }

  @Override
  public Map<String, Object> getReturnValues(String mpId, String jobId) throws TranscriptionServiceException {
    throw new TranscriptionServiceException("Method not implemented");
  }

  @Override
  public String getLanguage() {
    return language;
  }

  @Override
  public void transcriptionError(String mpId, Object obj)
          throws TranscriptionServiceException {
    logger.warn("Transcription error callback received but not supported.");
  }

  @Override
  public void transcriptionDone(String mpId, Object obj)
          throws TranscriptionServiceException {
    logger.info("Transcription done callback received (not used by Whisper service).");
  }

  @Override
  // Called by the attach workflow operation
  public MediaPackageElement getGeneratedTranscription(String mpId, String jobId, MediaPackageElement.Type type)
          throws TranscriptionServiceException {

    try (InputStream in = wfr.get(TRANSCRIPT_COLLECTION, mpId + ".vtt")) {
      URI workspaceUri = workspace.put(TRANSCRIPT_COLLECTION, mpId, "captions.vtt", in);
      logger.info("Stored transcript in workspace: {}", workspaceUri);

      MediaPackageElementBuilder builder = MediaPackageElementBuilderFactory.newInstance().newElementBuilder();
      logger.info("Returning MPE with results file URI: {}", workspaceUri);

      MediaPackageElement element = builder.elementFromURI(workspaceUri, MediaPackageElement.Type.Track,
          new MediaPackageElementFlavor("captions", "source"));

      element.setMimeType(MimeType.mimeType("text", "vtt"));

      return element;
    } catch (IOException ex) {
      logger.error("Unable to retrieve transcript, error: {}", ex.toString());
      throw new TranscriptionServiceException("Unable to retrieve transcript", ex);
    } catch (NotFoundException ex) {
      logger.error("Transcript not found, error: {}", ex.toString());
      throw new TranscriptionServiceException("Trannscript not found", ex);
    }

  }

  // Called when a transcription job has been returned
  protected void deleteStorageFile(String filename) throws IOException {
    try {
      wfr.deleteFromCollection(SUBMISSION_COLLECTION, filename, false);
    } catch (IOException e) {
      logger.warn("Unable to remove submission file {} from collection {}", filename, SUBMISSION_COLLECTION);
    }
  }

  // ===== AUDIO PROCESSING =====
  private File convertToMonoWav(File input) throws Exception {

    logger.info("input file to convert to mono: {}", input.getAbsolutePath());
    File output = File.createTempFile("whisper-", ".wav");

    ProcessBuilder pb = new ProcessBuilder(
            "ffmpeg",
            "-y",
            "-i", input.getAbsolutePath(),
            "-ac", "1",
            "-ar", "16000",
            output.getAbsolutePath()
    );

    pb.redirectErrorStream(true); // merge stderr into stdout
    Process process = pb.start();

    BufferedReader reader =
            new BufferedReader(new InputStreamReader(process.getInputStream()));

    String line;
    StringBuilder ffmpegOutput = new StringBuilder();
    while ((line = reader.readLine()) != null) {
      ffmpegOutput.append(line).append("\n");
    }

    logger.info("ffmPeg output: {}", ffmpegOutput);

    int exitCode = process.waitFor();

    if (exitCode != 0) {
      throw new RuntimeException(
              "FFmpeg conversion failed. Exit code: "
                      + exitCode
                      + "\nOutput:\n"
                      + ffmpegOutput
      );
    }

    return output;
  }

  private List<File> splitIntoChunks(File wav, int seconds) throws Exception {

    File dir = Files.createTempDirectory("whisper-chunks").toFile();
    String pattern = new File(dir, "chunk_%03d.wav").getAbsolutePath();

    ProcessBuilder pb = new ProcessBuilder(
            "ffmpeg", "-y",
            "-i", wav.getAbsolutePath(),
            "-f", "segment",
            "-segment_time", String.valueOf(seconds),
            "-c", "copy",
            pattern
    );

    if (pb.start().waitFor() != 0) {
      throw new RuntimeException("FFmpeg split failed");
    }

    File[] files = dir.listFiles();
    Arrays.sort(files);

    return Arrays.asList(files);
  }

  // ===== WHISPER API =====
  private String transcribeChunkWithRetry(File chunk) throws Exception {
    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        return transcribeChunk(chunk);
      } catch (Exception e) {
        if (attempt == 3) {
          throw e;
        }
        logger.warn("Retry {} for chunk {}", attempt, chunk.getName());
        Thread.sleep(2000);
      }
    }
    throw new RuntimeException("Unreachable");
  }

  private String transcribeChunk(File audioFile) throws Exception {
    RequestConfig config = RequestConfig.custom()
        .setConnectTimeout(TIMEOUT)
        .setSocketTimeout(TIMEOUT)
        .build();

    try (CloseableHttpClient client = HttpClientBuilder.create()
        .setDefaultRequestConfig(config)
        .build()) {

      HttpPost post = new HttpPost(WHISPER_URL);
      post.setHeader("Authorization", "Bearer " + apiKey);

      MultipartEntityBuilder builder = MultipartEntityBuilder.create();

      // builder.addBinaryBody(
      //         "file",
      //         audioFile,
      //         ContentType.create("audio/wav"),
      //         audioFile.getName()
      // );
      builder.addBinaryBody("file", audioFile);
      builder.addTextBody("model", "whisper-1");
      builder.addTextBody("response_format", "vtt");

      post.setEntity(builder.build());

      try (CloseableHttpResponse response = client.execute(post)) {
        int status = response.getStatusLine().getStatusCode();
        String transcript = EntityUtils.toString(response.getEntity());

        if (status != 200) {
          throw new RuntimeException(
                  "Whisper API error: HTTP " + status + " - " + transcript);
        }

        return transcript;
      }
    }
  }

  // ===== VTT HANDLING =====
  private String shiftVttTimestamps(String vtt, double offsetSeconds) {
    Pattern p = Pattern.compile("(\\d{2}:\\d{2}:\\d{2}\\.\\d{3}) --> (\\d{2}:\\d{2}:\\d{2}\\.\\d{3})");
    Matcher m = p.matcher(vtt);
    StringBuffer sb = new StringBuffer();

    while (m.find()) {
      String start = m.group(1);
      String end = m.group(2);

      m.appendReplacement(sb,
              formatTimestamp(addOffset(start, offsetSeconds))
                      + " --> "
                      + formatTimestamp(addOffset(end, offsetSeconds)));
    }

    m.appendTail(sb);
    return sb.toString();
  }

  // Converts hh:mm:ss.mmm to total seconds, adds offset, then returns total seconds
  private double addOffset(String timestamp, double offsetSeconds) {
    String[] hms = timestamp.split(":");
    if (hms.length != 3) {
      throw new IllegalArgumentException("Invalid timestamp: " + timestamp);
    }

    int hours = Integer.parseInt(hms[0]);
    int minutes = Integer.parseInt(hms[1]);
    double seconds = Double.parseDouble(hms[2]);

    double total = hours * 3600 + minutes * 60 + seconds + offsetSeconds;
    return Math.max(total, 0); // prevent negative times
  }

  // Converts seconds to hh:mm:ss.mmm string
  private String formatTimestamp(double totalSeconds) {
    int hours = (int) (totalSeconds / 3600);
    totalSeconds -= hours * 3600;
    int minutes = (int) (totalSeconds / 60);
    totalSeconds -= minutes * 60;
    int seconds = (int) totalSeconds;
    int millis = (int) Math.round((totalSeconds - seconds) * 1000);

    return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, millis);
  }
  // private String shiftVttTimestamps(String vtt, double offsetSeconds) {
  //   Pattern p = Pattern.compile(
  //           "(\\d{2}:\\d{2}:\\d{2}\\.\\d{3}) --> (\\d{2}:\\d{2}:\\d{2}\\.\\d{3})");

  //   Matcher m = p.matcher(vtt);
  //   StringBuffer sb = new StringBuffer();

  //   while (m.find()) {
  //     m.appendReplacement(sb,
  //             shift(m.group(1), offsetSeconds)
  //             + " --> "
  //             + shift(m.group(2), offsetSeconds));
  //   }

  //   m.appendTail(sb);
  //   return sb.toString();
  // }

  private String shift(String time, int offsetSeconds) {
    // Split hours, minutes, seconds
    String[] parts = time.split(":");
    if (parts.length != 3) {
      throw new IllegalArgumentException("Invalid timestamp format: " + time);
    }

    int hours = Integer.parseInt(parts[0]);
    int minutes = Integer.parseInt(parts[1]);

    // Split seconds and optional milliseconds
    String[] secParts = parts[2].split("\\.");
    int seconds = Integer.parseInt(secParts[0]);
    int millis = secParts.length > 1 ? Integer.parseInt(secParts[1]) : 0;

    // Convert everything to total milliseconds
    long totalMillis = ((hours * 3600L) + (minutes * 60L) + seconds + offsetSeconds) * 1000L + millis;

    // Handle negative offsets gracefully
    if (totalMillis < 0) {
      totalMillis = 0;
    }

    // Convert back to h:m:s.ms
    long h = totalMillis / 3600000;
    long m = (totalMillis % 3600000) / 60000;
    long s = (totalMillis % 60000) / 1000;
    long ms = totalMillis % 1000;

    return String.format("%02d:%02d:%02d.%03d", h, m, s, ms);
  }

  private String mergeVtts(List<String> vtts) {
    StringBuilder sb = new StringBuilder("WEBVTT\n\n");
    for (String vtt : vtts) {
      sb.append(vtt.replaceFirst("WEBVTT\\s*", ""))
          .append("\n");
    }
    return sb.toString();
  }

  private void cleanup(File wav, List<File> chunks) {
    wav.delete();
    for (File f : chunks) {
      f.delete();
    }
  }

  private static class ChunkResult {
    private final int index;
    private final String vtt;
    private final double durationSeconds;

    ChunkResult(int index, String vtt, double durationSeconds) {
      this.index = index;
      this.vtt = vtt;
      this.durationSeconds = durationSeconds;
    }
  }

  private double getAudioDurationSeconds(File wavFile) throws Exception {
    try (AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(wavFile)) {
      AudioFormat format = audioInputStream.getFormat();
      long frames = audioInputStream.getFrameLength();
      return frames / format.getFrameRate();
    }
  }

  private void triggerWhisperAttachWorkflow(String mpId, URI vttUri) throws Exception {
    // Set default system organization & user
    DefaultOrganization defaultOrg = new DefaultOrganization();
    securityService.setOrganization(defaultOrg);
    securityService.setUser(SecurityUtil.createSystemUser(systemAccount, defaultOrg));

    // Find the latest snapshot (episode)
    Optional<Snapshot> snapshot = assetManager.getLatestSnapshot(mpId);
    if (snapshot.isEmpty()) {
      logger.warn("Media package {} has not been archived yet. Skipped.", mpId);
      return;
    }

    String org = snapshot.get().getOrganizationId();
    Organization organization = organizationDirectoryService.getOrganization(org);
    if (organization == null) {
      logger.warn("Media package {} has an unknown organization {}. Skipped.", mpId, org);
      return;
    }
    securityService.setOrganization(organization);

    // Build workflow parameters
    Map<String, String> params = new HashMap<>();
    // For Whisper, we don't have a jobId, but you can still pass mpId or vttUri
    params.put("whisper.vtt.uri", vttUri.toString());

    WorkflowDefinition wfDef = workflowService.getWorkflowDefinitionById(workflowDefinitionId);

    // Apply workflow
    Workflows workflows = wfUtil != null ? wfUtil : new Workflows(assetManager, workflowService);
    // Set<String> mpIds = Collections.singleton(mpId);
    Set<String> mpIds = new HashSet<String>();
    mpIds.add(mpId);

    List<WorkflowInstance> wfList = workflows.applyWorkflowToLatestVersion(mpIds,
            ConfiguredWorkflow.workflow(wfDef, params));
    String wfId = wfList.size() > 0 ? Long.toString(wfList.get(0).getId()) : "Unknown";

    logger.info("Attach transcription workflow {} scheduled for mp {} (Whisper)", wfId, mpId);
  }

  @Reference
  public void setWorkflowService(WorkflowService service) {
    this.workflowService = service;
  }

  @Reference
  public void setServiceRegistry(ServiceRegistry serviceRegistry) {
    this.serviceRegistry = serviceRegistry;
  }

  @Reference
  public void setWorkspace(Workspace workspace) {
    this.workspace = workspace;
  }

  @Reference
  public void setWorkingFileRepository(WorkingFileRepository wfr) {
    this.wfr = wfr;
  }

  @Reference
  public void setAssetManager(AssetManager service) {
    this.assetManager = service;
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
}
