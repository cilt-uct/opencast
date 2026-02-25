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

// import org.opencastproject.job.api.Job;
// import org.apache.commons.lang3.StringUtils;
// import java.io.FileOutputStream;

package org.opencastproject.transcription.workflowoperation;

import org.opencastproject.job.api.Job;
import org.opencastproject.job.api.JobContext;
import org.opencastproject.mediapackage.MediaPackage;
import org.opencastproject.mediapackage.MediaPackageElement;
import org.opencastproject.mediapackage.MediaPackageElementFlavor;
import org.opencastproject.mediapackage.Track;
import org.opencastproject.mediapackage.selector.AbstractMediaPackageElementSelector;
import org.opencastproject.mediapackage.selector.TrackSelector;
import org.opencastproject.serviceregistry.api.ServiceRegistry;
import org.opencastproject.transcription.api.TranscriptionService;
import org.opencastproject.transcription.api.TranscriptionServiceException;
import org.opencastproject.workflow.api.AbstractWorkflowOperationHandler;
import org.opencastproject.workflow.api.ConfiguredTagsAndFlavors;
import org.opencastproject.workflow.api.WorkflowInstance;
import org.opencastproject.workflow.api.WorkflowOperationException;
import org.opencastproject.workflow.api.WorkflowOperationHandler;
import org.opencastproject.workflow.api.WorkflowOperationInstance;
import org.opencastproject.workflow.api.WorkflowOperationResult;
import org.opencastproject.workflow.api.WorkflowOperationResult.Action;
import org.opencastproject.workspace.api.Workspace;

import org.apache.commons.lang3.StringUtils;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

@Component(
    immediate = true,
    service = WorkflowOperationHandler.class,
    property = {
        "service.description=Whisper Start Transcription Workflow Operation Handler",
        "workflow.operation=whisper-start-transcription"
    }
)
public class WhisperStartTranscriptionOperationHandler extends AbstractWorkflowOperationHandler {

  private static final Logger logger = LoggerFactory.getLogger(WhisperStartTranscriptionOperationHandler.class);

  /** Workflow configuration option keys */
  static final String SKIP_IF_FLAVOR_EXISTS = "skip-if-flavor-exists";

  private static final String TRANSCRIPT_COLLECTION = "whisper-transcripts";

  /** The transcription service */
  private TranscriptionService service;

  private ServiceRegistry serviceRegistry;

  private Workspace workspace;

  @Override
  @Activate
  protected void activate(ComponentContext cc) {
    super.activate(cc);
    logger.info("Registering Whisper Start Transcription workflow operation handler");
  }

  @Override
  public WorkflowOperationResult start(WorkflowInstance workflowInstance, JobContext context)
          throws WorkflowOperationException {

    MediaPackage mediaPackage = workflowInstance.getMediaPackage();
    WorkflowOperationInstance operation = workflowInstance.getCurrentOperation();

    String skipOption = StringUtils.trimToNull(operation.getConfiguration(SKIP_IF_FLAVOR_EXISTS));
    if (skipOption != null) {
      MediaPackageElement[] mpes = mediaPackage.getElementsByFlavor(MediaPackageElementFlavor.parseFlavor(skipOption));
      if (mpes != null && mpes.length > 0) {
        logger.info(
                "Start transcription operation will be skipped because flavor {} already exists in the media package",
                skipOption);
        return createResult(Action.SKIP);
      }
    }

    logger.debug("Start transcription for mediapackage {}", mediaPackage);

      // Check which tags have been configured
    ConfiguredTagsAndFlavors tagsAndFlavors = getTagsAndFlavors(
        workflowInstance, Configuration.many, Configuration.many, Configuration.none, Configuration.none);
    List<String> sourceTagOption = tagsAndFlavors.getSrcTags();
    List<MediaPackageElementFlavor> sourceFlavorOption = tagsAndFlavors.getSrcFlavors();

    AbstractMediaPackageElementSelector<Track> elementSelector = new TrackSelector();

    // Make sure either one of tags or flavors are provided
    if (sourceTagOption.isEmpty() && sourceFlavorOption.isEmpty()) {
      throw new WorkflowOperationException("No source tag or flavor have been specified!");
    }

    if (!sourceFlavorOption.isEmpty()) {
      MediaPackageElementFlavor flavor = sourceFlavorOption.get(0);
      elementSelector.addFlavor(flavor);
    }
    if (!sourceTagOption.isEmpty()) {
      elementSelector.addTag(sourceTagOption.get(0));
    }

    Collection<Track> elements = elementSelector.select(mediaPackage, false);
    Job job = null;
    for (Track track : elements) {
      try {
        logger.info("Starting Whisper API transcription job for mpdId {}", mediaPackage.getIdentifier().toString());
        job = service.startTranscription(mediaPackage.getIdentifier().toString(), track);
        break;
      } catch (TranscriptionServiceException e) {
        throw new WorkflowOperationException(e);
      }
    }

    if (job == null) {
      logger.info("No matching tracks found");
      return createResult(mediaPackage, Action.CONTINUE);
    }


    logger.info("External transcription job for mediapackage {} was created", mediaPackage);

    // Results are empty, we should get a callback when transcription is done
    return createResult(Action.CONTINUE);
  }

  private void storeVttInWorkflow(WorkflowInstance workflowInstance, String vttText) throws Exception {
    File tmp = File.createTempFile("whisper-", ".vtt");
    java.nio.file.Files.writeString(tmp.toPath(), vttText, StandardCharsets.UTF_8);

    URI vttUri;
    try (FileInputStream in = new FileInputStream(tmp)) {
      vttUri = workspace.putInCollection(TRANSCRIPT_COLLECTION, tmp.getName(), in);
    }

    logger.info("Stored Whisper VTT in workspace with URI: {}", vttUri);

    // Save URI in workflow instance configuration
    WorkflowOperationInstance op = workflowInstance.getCurrentOperation();
    op.setConfiguration("whisper.vtt.uri", vttUri.toString());

    tmp.delete();
  }

  private Track selectTrack(MediaPackage mp) throws WorkflowOperationException {
    for (Track t : mp.getTracks()) {
      MediaPackageElementFlavor f = t.getFlavor();
      logger.info("Checking track flavor: {}", f);

      // pick any track with subtype 'transcription', video or audio
      if (f != null && "transcription".equals(f.getSubtype())) {
        return t;
      }
    }

    throw new WorkflowOperationException(
      "No tracks found with subtype 'transcription' in MediaPackage " + mp.getIdentifier()
    );
  }

  @Reference(target = "(provider=whisper)")
  public void setTranscriptionService(TranscriptionService service) {
    this.service = service;
  }

  @Reference
  public void setWorkspace(Workspace workspace) {
    this.workspace = workspace;
  }

  @Reference
  @Override
  public void setServiceRegistry(ServiceRegistry serviceRegistry) {
    super.setServiceRegistry(serviceRegistry);
    this.serviceRegistry = serviceRegistry;
  }
}
