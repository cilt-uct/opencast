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

import org.opencastproject.archive.api.Archive;
import org.opencastproject.archive.api.HttpMediaPackageElementProvider;
import org.opencastproject.archive.api.Query;
import org.opencastproject.archive.api.ResultItem;
import org.opencastproject.archive.api.ResultSet;
import org.opencastproject.archive.api.UriRewriter;
import org.opencastproject.kernel.mail.SmtpService;
import org.opencastproject.mediapackage.MediaPackage;
import org.opencastproject.mediapackage.MediaPackageBuilder;
import org.opencastproject.mediapackage.MediaPackageBuilderFactory;
import org.opencastproject.mediapackage.MediaPackageElement;
import org.opencastproject.mediapackage.Track;
import org.opencastproject.security.api.DefaultOrganization;
import org.opencastproject.security.api.JaxbOrganization;
import org.opencastproject.security.api.OrganizationDirectoryService;
import org.opencastproject.security.api.SecurityService;
import org.opencastproject.security.api.UserDirectoryService;
import org.opencastproject.serviceregistry.api.IncidentService;
import org.opencastproject.serviceregistry.api.ServiceRegistry;
import org.opencastproject.serviceregistry.api.ServiceRegistryInMemoryImpl;
import org.opencastproject.systems.OpencastConstants;
import org.opencastproject.transcription.api.TranscriptionServiceException;
import org.opencastproject.transcription.nibity.NibityTranscriptionService.WorkflowDispatcher;
import org.opencastproject.transcription.nibity.persistence.NibityTranscriptionDatabase;
import org.opencastproject.transcription.nibity.persistence.NibityTranscriptionJobControl;
import org.opencastproject.transcription.nibity.persistence.TranscriptionProviderControl;
import org.opencastproject.util.NotFoundException;
import org.opencastproject.util.persistence.PersistenceUtil;
import org.opencastproject.workflow.api.ConfiguredWorkflow;
import org.opencastproject.workflow.api.WorkflowDefinition;
import org.opencastproject.workflow.api.WorkflowDefinitionImpl;
import org.opencastproject.workflow.api.WorkflowInstance;
import org.opencastproject.workflow.api.WorkflowInstanceImpl;
import org.opencastproject.workflow.api.WorkflowService;
import org.opencastproject.workspace.api.Workspace;

import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

public class NibityTranscriptionServiceTest {

  private static final String MP_ID = "mpId1";
  private static final String TRACK_ID = "audioTrack1";
  private static final String JOB_ID = "jobId1";
  private static final long TRACK_DURATION = 60000;
  private static final String CLIENT_ID = "clientId";
  private static final String CLIENT_SECRET = "secret";
  private static final String CLIENT_TOKEN = "token";
  private static final String ACCES_TOKEN = "access";
  private static final String PULLED_TRANSCRIPTION_FILE = "pulled_google_transcription.json";
  private static final String IN_PROGRESS_JOB = "in_progress_job.json";
  private static final String PROVIDER = "Google Speech";
  private static final long PROVIDER_ID = 1;
  private static final String LANGUAGE_CODE = "en-US";

  private CloseableHttpClient httpClient;
  private MediaPackage mediaPackage;
  private final JSONParser jsonParser = new JSONParser();
  private File audioFile;

  private NibityTranscriptionService service;
  private NibityTranscriptionDatabase database;
  private Workspace workspace;
  protected Archive<?> archive = null;
  private WorkflowService wfService;
  private JaxbOrganization org;
  private HttpMediaPackageElementProvider httpMediaPackageElementProvider;
  private SmtpService smtpService;

  @Before
  public void setUp() throws Exception {
    MediaPackageBuilder builder = MediaPackageBuilderFactory.newInstance().newMediaPackageBuilder();

    URI mediaPackageURI = NibityTranscriptionServiceTest.class.getResource("/mp.xml").toURI();
    mediaPackage = builder.loadFromXml(mediaPackageURI.toURL().openStream());

    URI audioUrl = NibityTranscriptionServiceTest.class.getResource("/audio.flac").toURI();
    audioFile = new File(audioUrl);

    Dictionary<String, Object> props = new Hashtable<String, Object>();
    props.put(NibityTranscriptionService.ENABLED_CONFIG, "true");
    props.put(NibityTranscriptionService.GOOGLE_CLOUD_CLIENT_ID, CLIENT_ID);
    props.put(NibityTranscriptionService.GOOGLE_CLOUD_CLIENT_SECRET, CLIENT_SECRET);
    props.put(NibityTranscriptionService.GOOGLE_CLOUD_REFRESH_TOKEN, CLIENT_TOKEN);
    props.put(NibityTranscriptionService.GOOGLE_CLOUD_BUCKET, "bucket");
    props.put(NibityTranscriptionService.COMPLETION_CHECK_BUFFER_CONFIG, 0);
    props.put(NibityTranscriptionService.MAX_PROCESSING_TIME_CONFIG, 0);
    props.put(NibityTranscriptionService.NOTIFICATION_EMAIL_CONFIG, "anyone@opencast.org");

    ComponentContext cc = EasyMock.createNiceMock(ComponentContext.class);
    EasyMock.expect(cc.getProperties()).andReturn(props).anyTimes();
    BundleContext bc = EasyMock.createNiceMock(BundleContext.class);
    EasyMock.expect(bc.getProperty(OpencastConstants.SERVER_URL_PROPERTY)).andReturn("http://THIS_SERVER");
    EasyMock.expect(bc.getProperty("org.opencastproject.security.digest.user")).andReturn("opencast_system_account");
    EasyMock.expect(cc.getBundleContext()).andReturn(bc).anyTimes();

    SecurityService securityService = EasyMock.createNiceMock(SecurityService.class);
    Map<String, String> orgProps = new HashMap<String, String>();
    orgProps.put(NibityTranscriptionService.ADMIN_URL_PROPERTY, "http://ADMIN_SERVER");
    org = new JaxbOrganization(DefaultOrganization.DEFAULT_ORGANIZATION_ID,
            DefaultOrganization.DEFAULT_ORGANIZATION_NAME, null, DefaultOrganization.DEFAULT_ORGANIZATION_ADMIN,
            DefaultOrganization.DEFAULT_ORGANIZATION_ANONYMOUS, orgProps);
    EasyMock.expect(securityService.getOrganization()).andReturn(org).anyTimes();

    OrganizationDirectoryService orgDirectory = EasyMock.createNiceMock(OrganizationDirectoryService.class);
    EasyMock.expect(orgDirectory.getOrganization((String) EasyMock.anyObject())).andReturn(org).anyTimes();
    UserDirectoryService userDirectory = EasyMock.createNiceMock(UserDirectoryService.class);

    IncidentService incident = EasyMock.createNiceMock(IncidentService.class);

    smtpService = EasyMock.createNiceMock(SmtpService.class);
    smtpService.send((String) EasyMock.anyObject(), (String) EasyMock.anyObject(), (String) EasyMock.anyObject());
    EasyMock.expectLastCall().once();

    EasyMock.replay(bc, cc, securityService, orgDirectory, userDirectory, incident, smtpService);

    // Mocks for WorkflowDispatcher test
    archive = EasyMock.createNiceMock(Archive.class);
    wfService = EasyMock.createNiceMock(WorkflowService.class);

    httpMediaPackageElementProvider = EasyMock.createNiceMock(HttpMediaPackageElementProvider.class);
    EasyMock.expect(httpMediaPackageElementProvider.getUriRewriter()).andReturn(null); // it's ok, won't be used
    EasyMock.replay(httpMediaPackageElementProvider);

    workspace = EasyMock.createNiceMock(Workspace.class);

    // Database
    database = new NibityTranscriptionDatabase() {
      @Override
      public TranscriptionProviderControl findIdByProvider(String provider) {
        return new TranscriptionProviderControl(PROVIDER_ID, PROVIDER);
      }
    };
    database.setEntityManagerFactory(
            PersistenceUtil.newTestEntityManagerFactory("org.opencastproject.transcription.nibity.persistence"));
    database.activate(null);

    httpClient = EasyMock.createNiceMock(CloseableHttpClient.class);

    service = new NibityTranscriptionService() {
      @Override
      protected CloseableHttpClient makeHttpClient() {
        return httpClient;
      }

      @Override
      protected String getRefreshAccessToken() {
        return ACCES_TOKEN;
      }

      @Override
      protected String uploadAudioFileToGoogleStorage(String mediapackage, Track track) {
        return "audioURL";
      }

      @Override
      protected void deleteStorageFile(String mpId, String token) {

      }
    };
    ServiceRegistry serviceRegistry = new ServiceRegistryInMemoryImpl(service, securityService, userDirectory,
            orgDirectory, incident);
    service.setOrganizationDirectoryService(orgDirectory);
    service.setSecurityService(securityService);
    service.setServiceRegistry(serviceRegistry);
    service.setUserDirectoryService(userDirectory);
    service.setWorkspace(workspace);
    service.setDatabase(database);
    service.setArchive(archive);
    service.setWorkflowService(wfService);
    service.setSmtpService(smtpService);
    service.setHttpMediaPackageElementProvider(httpMediaPackageElementProvider);
    service.activate(cc);
  }

  @After
  public void tearDown() throws Exception {
  }

  @Test
  public void testCreateRecognitionsJob() throws Exception {
    InputStream stream = NibityTranscriptionServiceTest.class.getResourceAsStream("/" + IN_PROGRESS_JOB);

    Capture<String> capturedCollection = Capture.newInstance();
    Capture<String> capturedFileName = Capture.newInstance();
    EasyMock.expect(workspace.putInCollection(EasyMock.capture(capturedCollection), EasyMock.capture(capturedFileName),
            EasyMock.anyObject(InputStream.class))).andReturn(new URI("http://anything"));
    EasyMock.replay(workspace);

    HttpEntity httpEntity = EasyMock.createNiceMock(HttpEntity.class);
    EasyMock.expect(httpEntity.getContent()).andReturn(stream);

    CloseableHttpResponse response = EasyMock.createNiceMock(CloseableHttpResponse.class);
    StatusLine status1 = EasyMock.createNiceMock(StatusLine.class);
    EasyMock.expect(response.getStatusLine()).andReturn(status1).anyTimes();
    EasyMock.expect(response.getEntity()).andReturn(httpEntity).anyTimes();
    EasyMock.expect(status1.getStatusCode()).andReturn(HttpStatus.SC_OK).anyTimes();
    EasyMock.replay(httpEntity, response, status1);

    Capture<HttpPost> capturedPost = Capture.newInstance();
    EasyMock.expect(httpClient.execute(EasyMock.capture(capturedPost))).andReturn(response).anyTimes();
    EasyMock.replay(httpClient);

    service.createRecognitionsJob(MP_ID, mediaPackage.getTrack("audioTrack1"), LANGUAGE_CODE);
    Assert.assertEquals("https://speech.googleapis.com/v1/speech:longrunningrecognize",
            capturedPost.getValue().getURI().toString());

    NibityTranscriptionJobControl j = database.findByJob(JOB_ID);
    Assert.assertNotNull(j);
    Assert.assertEquals(MP_ID, j.getMediaPackageId());
    Assert.assertEquals(TRACK_ID, j.getTrackId());
    Assert.assertEquals(NibityTranscriptionJobControl.Status.Progress.name(), j.getStatus());
  }

  @Test(expected = TranscriptionServiceException.class)
  public void testCreateRecognitionsJobErrors() throws Exception {
    EasyMock.expect(workspace.get(EasyMock.anyObject(URI.class))).andReturn(audioFile);
    EasyMock.replay(workspace);

    CloseableHttpResponse response = EasyMock.createNiceMock(CloseableHttpResponse.class);
    StatusLine status = EasyMock.createNiceMock(StatusLine.class);
    EasyMock.expect(response.getStatusLine()).andReturn(status).anyTimes();
    EasyMock.replay(response, status);

    Capture<HttpPost> capturedPost = Capture.newInstance();
    EasyMock.expect(httpClient.execute(EasyMock.capture(capturedPost))).andReturn(response).anyTimes();
    EasyMock.replay(httpClient);

    service.createRecognitionsJob(MP_ID, mediaPackage.getTrack("audioTrack1"), LANGUAGE_CODE);
  }

  @Test
  public void testTranscriptionDone() throws Exception {
    InputStream stream = NibityTranscriptionServiceTest.class.getResourceAsStream("/" + PULLED_TRANSCRIPTION_FILE);
    database.storeJobControl(MP_ID, TRACK_ID, JOB_ID, NibityTranscriptionJobControl.Status.Progress.name(), TRACK_DURATION, PROVIDER);
    JSONObject obj = (JSONObject) jsonParser.parse(new InputStreamReader(stream));

    Capture<String> capturedCollection = Capture.newInstance();
    Capture<String> capturedFileName = Capture.newInstance();
    EasyMock.expect(workspace.putInCollection(EasyMock.capture(capturedCollection), EasyMock.capture(capturedFileName),
            EasyMock.anyObject(InputStream.class))).andReturn(new URI("http://anything"));
    EasyMock.replay(workspace);

    long before = System.currentTimeMillis();
    service.transcriptionDone(MP_ID, obj);
    long after = System.currentTimeMillis();
    // Check if status and date in db was updated
    NibityTranscriptionJobControl job = database.findByJob(JOB_ID);
    Assert.assertNotNull(job);
    Assert.assertEquals(NibityTranscriptionJobControl.Status.TranscriptionComplete.name(), job.getStatus());
    Assert.assertNotNull(job.getDateCompleted());
    Assert.assertTrue(before <= job.getDateCompleted().getTime() && job.getDateCompleted().getTime() <= after);
    // Check if results were saved into a collection
    Assert.assertEquals(NibityTranscriptionService.TRANSCRIPT_COLLECTION, capturedCollection.getValue());
    Assert.assertEquals(JOB_ID + ".json", capturedFileName.getValue());
  }

  @Test
  public void testTranscriptionError() throws Exception {
    InputStream stream = NibityTranscriptionServiceTest.class.getResourceAsStream("/" + PULLED_TRANSCRIPTION_FILE);
    database.storeJobControl(MP_ID, TRACK_ID, JOB_ID, NibityTranscriptionJobControl.Status.Progress.name(), TRACK_DURATION, PROVIDER);
    JSONObject obj = (JSONObject) jsonParser.parse(new InputStreamReader(stream));

    service.transcriptionError(MP_ID, obj);
    // Check if status and date in db was updated
    NibityTranscriptionJobControl job = database.findByJob(JOB_ID);
    Assert.assertNotNull(job);
    Assert.assertEquals(NibityTranscriptionJobControl.Status.Error.name(), job.getStatus());
    Assert.assertNull(job.getDateCompleted());

    EasyMock.verify(smtpService);
  }

  @Test
  public void testGetAndSaveJobResults() throws Exception {
    InputStream stream = NibityTranscriptionServiceTest.class.getResourceAsStream("/" + PULLED_TRANSCRIPTION_FILE);
    database.storeJobControl(MP_ID, TRACK_ID, JOB_ID, NibityTranscriptionJobControl.Status.Progress.name(), TRACK_DURATION, PROVIDER);

    Capture<String> capturedCollection = Capture.newInstance();
    Capture<String> capturedFileName = Capture.newInstance();
    EasyMock.expect(workspace.putInCollection(EasyMock.capture(capturedCollection), EasyMock.capture(capturedFileName),
            EasyMock.anyObject(InputStream.class))).andReturn(new URI("http://anything"));
    EasyMock.replay(workspace);

    HttpEntity httpEntity = EasyMock.createNiceMock(HttpEntity.class);
    EasyMock.expect(httpEntity.getContent()).andReturn(stream);

    CloseableHttpResponse response = EasyMock.createNiceMock(CloseableHttpResponse.class);
    StatusLine status = EasyMock.createNiceMock(StatusLine.class);
    EasyMock.expect(response.getStatusLine()).andReturn(status).anyTimes();
    EasyMock.expect(response.getEntity()).andReturn(httpEntity).anyTimes();
    EasyMock.expect(status.getStatusCode()).andReturn(HttpStatus.SC_OK).anyTimes();
    EasyMock.replay(httpEntity, response, status);

    Capture<HttpGet> capturedGet = Capture.newInstance();
    EasyMock.expect(httpClient.execute(EasyMock.capture(capturedGet))).andReturn(response).anyTimes();
    EasyMock.replay(httpClient);

    long before = System.currentTimeMillis();
    service.getAndSaveJobResults(JOB_ID);
    long after = System.currentTimeMillis();
    // Check if correct url was invoked
    Assert.assertEquals("https://speech.googleapis.com/v1/operations/" + JOB_ID,
            capturedGet.getValue().getURI().toString());
    // Check if status and date in db was updated
    NibityTranscriptionJobControl job = database.findByJob(JOB_ID);
    Assert.assertNotNull(job);
    Assert.assertEquals(NibityTranscriptionJobControl.Status.TranscriptionComplete.name(), job.getStatus());
    Assert.assertNotNull(job.getDateCompleted());
    Assert.assertTrue(before <= job.getDateCompleted().getTime() && job.getDateCompleted().getTime() <= after);
    // Check if results were saved into a collection
    Assert.assertEquals(NibityTranscriptionService.TRANSCRIPT_COLLECTION, capturedCollection.getValue());
    Assert.assertEquals(JOB_ID + ".json", capturedFileName.getValue());
  }

  @Test
  public void testRefreshAccessTokenURL() throws Exception {
    CloseableHttpResponse response = EasyMock.createNiceMock(CloseableHttpResponse.class);
    StatusLine status = EasyMock.createNiceMock(StatusLine.class);
    EasyMock.expect(response.getStatusLine()).andReturn(status).anyTimes();
    EasyMock.expect(status.getStatusCode()).andReturn(HttpStatus.SC_NOT_FOUND).anyTimes();
    EasyMock.replay(response, status);

    Capture<HttpPost> capturedPost = Capture.newInstance();
    EasyMock.expect(httpClient.execute(EasyMock.capture(capturedPost))).andReturn(response).anyTimes();
    EasyMock.replay(httpClient);

    String token = service.refreshAccessToken(CLIENT_ID, CLIENT_SECRET, CLIENT_TOKEN);
    Assert.assertEquals("client_id=" + CLIENT_ID + "&client_secret=" + CLIENT_SECRET
            + "&refresh_token=" + CLIENT_TOKEN + "&grant_type=refresh_token",
            capturedPost.getValue().getURI().getQuery());
    Assert.assertEquals("-1", token);
  }

  @Test
  public void testGetAndSaveJobResultsError503() throws Exception {
    CloseableHttpResponse response = EasyMock.createNiceMock(CloseableHttpResponse.class);
    StatusLine status = EasyMock.createNiceMock(StatusLine.class);
    EasyMock.expect(response.getStatusLine()).andReturn(status).anyTimes();
    EasyMock.expect(status.getStatusCode()).andReturn(HttpStatus.SC_SERVICE_UNAVAILABLE).anyTimes();
    EasyMock.replay(response, status);

    Capture<HttpGet> capturedGet = Capture.newInstance();
    EasyMock.expect(httpClient.execute(EasyMock.capture(capturedGet))).andReturn(response).anyTimes();
    EasyMock.replay(httpClient);

    try {
      service.getAndSaveJobResults(JOB_ID);
    } catch (TranscriptionServiceException e) {
      Assert.assertEquals(503, e.getCode());
    }
  }

  @Test
  public void testGetGeneratedTranscriptionNoJobId() throws Exception {
    database.storeJobControl(MP_ID, TRACK_ID, JOB_ID, NibityTranscriptionJobControl.Status.Progress.name(), TRACK_DURATION, PROVIDER);
    database.storeJobControl(MP_ID, "audioTrack2", "jobId2", NibityTranscriptionJobControl.Status.Progress.name(),
            TRACK_DURATION, PROVIDER);
    database.updateJobControl(JOB_ID, NibityTranscriptionJobControl.Status.TranscriptionComplete.name());

    URI uri = new URI("http://ADMIN_SERVER/collection/" + NibityTranscriptionService.TRANSCRIPT_COLLECTION + "/"
            + JOB_ID + ".json");
    EasyMock.expect(workspace.getCollectionURI(NibityTranscriptionService.TRANSCRIPT_COLLECTION, JOB_ID + ".json"))
            .andReturn(uri);
    EasyMock.expect(workspace.get(uri)).andReturn(null); // Doesn't matter what is returned
    EasyMock.replay(workspace);

    MediaPackageElement mpe = service.getGeneratedTranscription(MP_ID, null);
    Assert.assertEquals("captions", mpe.getFlavor().getType());
    Assert.assertEquals("google-speech-json", mpe.getFlavor().getSubtype());
    Assert.assertEquals(uri.toString(), mpe.getURI().toString());
  }

  @Test
  public void testGetGeneratedTranscriptionNotInWorkspace() throws Exception {
    InputStream stream = NibityTranscriptionServiceTest.class.getResourceAsStream("/" + PULLED_TRANSCRIPTION_FILE);

    database.storeJobControl(MP_ID, TRACK_ID, JOB_ID, NibityTranscriptionJobControl.Status.Progress.name(), TRACK_DURATION, PROVIDER);

    URI uri = new URI("http://ADMIN_SERVER/collection/" + NibityTranscriptionService.TRANSCRIPT_COLLECTION + "/"
            + JOB_ID + ".json");
    EasyMock.expect(workspace.getCollectionURI(NibityTranscriptionService.TRANSCRIPT_COLLECTION, JOB_ID + ".json"))
            .andReturn(uri);
    EasyMock.expect(workspace.get(uri)).andThrow(new NotFoundException());
    EasyMock.expect(workspace.putInCollection(EasyMock.anyObject(String.class), EasyMock.anyObject(String.class),
            EasyMock.anyObject(InputStream.class))).andReturn(uri);
    EasyMock.replay(workspace);

    HttpEntity httpEntity = EasyMock.createNiceMock(HttpEntity.class);
    EasyMock.expect(httpEntity.getContent()).andReturn(stream);

    CloseableHttpResponse response = EasyMock.createNiceMock(CloseableHttpResponse.class);
    StatusLine status = EasyMock.createNiceMock(StatusLine.class);
    EasyMock.expect(response.getStatusLine()).andReturn(status).anyTimes();
    EasyMock.expect(response.getEntity()).andReturn(httpEntity).anyTimes();
    EasyMock.expect(status.getStatusCode()).andReturn(HttpStatus.SC_OK).anyTimes();
    EasyMock.replay(httpEntity, response, status);

    EasyMock.expect(httpClient.execute(EasyMock.anyObject(HttpGet.class))).andReturn(response).anyTimes();
    EasyMock.replay(httpClient);

    MediaPackageElement mpe = service.getGeneratedTranscription(MP_ID, JOB_ID);
    Assert.assertEquals("captions", mpe.getFlavor().getType());
    Assert.assertEquals("google-speech-json", mpe.getFlavor().getSubtype());
    Assert.assertEquals(uri.toString(), mpe.getURI().toString());
  }

  @Test
  public void testWorkflowDispatcherRunTranscriptionCompletedState() throws Exception {

    database.storeJobControl(MP_ID, TRACK_ID, JOB_ID, NibityTranscriptionJobControl.Status.Progress.name(), TRACK_DURATION, PROVIDER);
    database.storeJobControl(MP_ID, "audioTrack2", "jobId2", NibityTranscriptionJobControl.Status.Progress.name(),
            TRACK_DURATION, PROVIDER);
    database.storeJobControl("mpId2", "audioTrack3", "jobId3", NibityTranscriptionJobControl.Status.Progress.name(),
            TRACK_DURATION, PROVIDER);
    database.updateJobControl(JOB_ID, NibityTranscriptionJobControl.Status.TranscriptionComplete.name());

    ResultItem item = EasyMock.createNiceMock(ResultItem.class);
    EasyMock.expect(item.getOrganizationId()).andReturn(org.getId());
    List<ResultItem> items = new ArrayList<ResultItem>();
    items.add(item);
    ResultSet result = EasyMock.createNiceMock(ResultSet.class);
    EasyMock.expect(result.getItems()).andReturn(items).anyTimes();
    EasyMock.expect(archive.findForAdministrativeRead(EasyMock.anyObject(Query.class),
            EasyMock.anyObject(UriRewriter.class))).andReturn(result);
    Capture<List<String>> capturedMpIds = new Capture<List<String>>();
    WorkflowDefinition wfDef = new WorkflowDefinitionImpl();
    EasyMock.expect(wfService.getWorkflowDefinitionById("DCE-attach-transcripts")).andReturn(wfDef);
    List<WorkflowInstance> wfList = new ArrayList<WorkflowInstance>();
    wfList.add(new WorkflowInstanceImpl());
    EasyMock.expect(archive.applyWorkflow(EasyMock.anyObject(ConfiguredWorkflow.class),
            EasyMock.anyObject(UriRewriter.class), EasyMock.capture(capturedMpIds))).andReturn(wfList);
    EasyMock.replay(item, result, archive, wfService);

    WorkflowDispatcher dispatcher = service.new WorkflowDispatcher();
    dispatcher.run();

    // Check if only one mp has a workflow created for it
    Assert.assertEquals(1, capturedMpIds.getValue().size());
    // And if it was the correct one
    Assert.assertEquals(MP_ID, capturedMpIds.getValue().get(0));
    // Check if status in db was updated
    NibityTranscriptionJobControl job = database.findByJob(JOB_ID);
    Assert.assertNotNull(job);
    Assert.assertEquals(NibityTranscriptionJobControl.Status.Closed.name(), job.getStatus());
  }

  @Test
  public void testWorkflowDispatcherRunProgressState() throws Exception {
    InputStream stream = NibityTranscriptionServiceTest.class.getResourceAsStream("/" + PULLED_TRANSCRIPTION_FILE);

    database.storeJobControl(MP_ID, TRACK_ID, JOB_ID, NibityTranscriptionJobControl.Status.Progress.name(), 0, PROVIDER);
    database.storeJobControl(MP_ID, "audioTrack2", "jobId2", NibityTranscriptionJobControl.Status.Progress.name(),
            TRACK_DURATION, PROVIDER);
    database.storeJobControl("mpId2", "audioTrack3", "jobId3", NibityTranscriptionJobControl.Status.Progress.name(),
            TRACK_DURATION, PROVIDER);

    EasyMock.expect(workspace.putInCollection(EasyMock.anyObject(String.class), EasyMock.anyObject(String.class),
            EasyMock.anyObject(InputStream.class))).andReturn(new URI("http://anything"));
    EasyMock.replay(workspace);

    HttpEntity httpEntity = EasyMock.createNiceMock(HttpEntity.class);
    EasyMock.expect(httpEntity.getContent()).andReturn(stream);

    CloseableHttpResponse response = EasyMock.createNiceMock(CloseableHttpResponse.class);
    StatusLine status = EasyMock.createNiceMock(StatusLine.class);
    EasyMock.expect(response.getStatusLine()).andReturn(status).anyTimes();
    EasyMock.expect(response.getEntity()).andReturn(httpEntity).anyTimes();
    EasyMock.expect(status.getStatusCode()).andReturn(HttpStatus.SC_OK).anyTimes();
    EasyMock.replay(httpEntity, response, status);

    Capture<HttpGet> capturedGet = new Capture<HttpGet>();
    EasyMock.expect(httpClient.execute(EasyMock.capture(capturedGet))).andReturn(response).anyTimes();
    EasyMock.replay(httpClient);

    ResultItem item = EasyMock.createNiceMock(ResultItem.class);
    EasyMock.expect(item.getOrganizationId()).andReturn(org.getId());
    List<ResultItem> items = new ArrayList<ResultItem>();
    items.add(item);
    ResultSet result = EasyMock.createNiceMock(ResultSet.class);
    EasyMock.expect(result.getItems()).andReturn(items).anyTimes();
    EasyMock.expect(archive.findForAdministrativeRead(EasyMock.anyObject(Query.class),
            EasyMock.anyObject(UriRewriter.class))).andReturn(result);
    Capture<List<String>> capturedMpIds = new Capture<List<String>>();
    WorkflowDefinition wfDef = new WorkflowDefinitionImpl();
    EasyMock.expect(wfService.getWorkflowDefinitionById("DCE-attach-transcripts")).andReturn(wfDef);
    List<WorkflowInstance> wfList = new ArrayList<WorkflowInstance>();
    wfList.add(new WorkflowInstanceImpl());
    EasyMock.expect(archive.applyWorkflow(EasyMock.anyObject(ConfiguredWorkflow.class),
            EasyMock.anyObject(UriRewriter.class), EasyMock.capture(capturedMpIds))).andReturn(wfList);
    EasyMock.replay(item, result, archive, wfService);

    WorkflowDispatcher dispatcher = service.new WorkflowDispatcher();
    dispatcher.run();

    // Check if it called the external service to get the results
    Assert.assertEquals("https://speech.googleapis.com/v1/operations/" + JOB_ID,
            capturedGet.getValue().getURI().toString());
    // Check if only one mp has a workflow created for it
    Assert.assertEquals(1, capturedMpIds.getValue().size());
    // And if it was the correct one
    Assert.assertEquals(MP_ID, capturedMpIds.getValue().get(0));
    // Check if status in db was updated
    NibityTranscriptionJobControl job = database.findByJob(JOB_ID);
    Assert.assertNotNull(job);
    Assert.assertEquals(NibityTranscriptionJobControl.Status.Closed.name(), job.getStatus());
  }

  @Test
  public void testWorkflowDispatcherJobNotFound() throws Exception {
    CloseableHttpResponse response = EasyMock.createNiceMock(CloseableHttpResponse.class);
    StatusLine status = EasyMock.createNiceMock(StatusLine.class);
    EasyMock.expect(response.getStatusLine()).andReturn(status).anyTimes();
    EasyMock.expect(status.getStatusCode()).andReturn(HttpStatus.SC_NOT_FOUND).anyTimes();
    EasyMock.replay(response, status);

    Capture<HttpGet> capturedGet = Capture.newInstance();
    EasyMock.expect(httpClient.execute(EasyMock.capture(capturedGet))).andReturn(response).anyTimes();
    EasyMock.replay(httpClient);

    database.storeJobControl(MP_ID, TRACK_ID, JOB_ID, NibityTranscriptionJobControl.Status.Progress.name(), 0, PROVIDER);

    EasyMock.replay(workspace);

    WorkflowDispatcher dispatcher = service.new WorkflowDispatcher();
    dispatcher.run();

    // Check if it called the external service to get the results
    Assert.assertEquals("https://speech.googleapis.com/v1/operations/" + JOB_ID,
            capturedGet.getValue().getURI().toString());

    // Check if the job status was updated and email was sent
    NibityTranscriptionJobControl j = database.findByJob(JOB_ID);
    Assert.assertNotNull(j);
    Assert.assertEquals(NibityTranscriptionJobControl.Status.Canceled.toString(), j.getStatus());
    EasyMock.verify(smtpService);
  }

  @Test
  public void testWorkflowDispatcherJobInProgressTooLong() throws Exception {
    InputStream stream = NibityTranscriptionServiceTest.class.getResourceAsStream("/" + IN_PROGRESS_JOB);

    HttpEntity httpEntity = EasyMock.createNiceMock(HttpEntity.class);
    EasyMock.expect(httpEntity.getContent()).andReturn(stream);

    CloseableHttpResponse response = EasyMock.createNiceMock(CloseableHttpResponse.class);
    StatusLine status = EasyMock.createNiceMock(StatusLine.class);
    EasyMock.expect(response.getStatusLine()).andReturn(status).anyTimes();
    EasyMock.expect(response.getEntity()).andReturn(httpEntity).anyTimes();
    EasyMock.expect(status.getStatusCode()).andReturn(HttpStatus.SC_OK).anyTimes();
    EasyMock.replay(httpEntity, response, status);

    Capture<HttpGet> capturedGet = Capture.newInstance();
    EasyMock.expect(httpClient.execute(EasyMock.capture(capturedGet))).andReturn(response).anyTimes();
    EasyMock.replay(httpClient);

    database.storeJobControl(MP_ID, TRACK_ID, JOB_ID, NibityTranscriptionJobControl.Status.Progress.name(), 0, PROVIDER);

    EasyMock.replay(workspace);

    WorkflowDispatcher dispatcher = service.new WorkflowDispatcher();
    dispatcher.run();

    // Check if it called the external service to get the results
    Assert.assertEquals("https://speech.googleapis.com/v1/operations/" + JOB_ID,
            capturedGet.getValue().getURI().toString());

    // Check if the job status was updated and email was sent
    NibityTranscriptionJobControl j = database.findByJob(JOB_ID);
    Assert.assertNotNull(j);
    Assert.assertEquals(NibityTranscriptionJobControl.Status.Canceled.toString(), j.getStatus());
    EasyMock.verify(smtpService);
  }

}
