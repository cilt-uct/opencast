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

// import org.opencastproject.workingfilerepository.api.WorkingFileRepository;
// import org.apache.commons.lang3.StringUtils;

package org.opencastproject.transcription.workflowoperation;

import org.opencastproject.caption.api.CaptionService;
import org.opencastproject.job.api.JobContext;
import org.opencastproject.mediapackage.MediaPackage;
import org.opencastproject.mediapackage.MediaPackageElementBuilder;
import org.opencastproject.mediapackage.MediaPackageElementBuilderFactory;
import org.opencastproject.mediapackage.MediaPackageElementFlavor;
import org.opencastproject.mediapackage.Track;
import org.opencastproject.serviceregistry.api.ServiceRegistry;
import org.opencastproject.transcription.api.TranscriptionService;
import org.opencastproject.util.MimeType;
import org.opencastproject.util.NotFoundException;
import org.opencastproject.workflow.api.AbstractWorkflowOperationHandler;
import org.opencastproject.workflow.api.WorkflowInstance;
import org.opencastproject.workflow.api.WorkflowOperationException;
import org.opencastproject.workflow.api.WorkflowOperationHandler;
import org.opencastproject.workflow.api.WorkflowOperationInstance;
import org.opencastproject.workflow.api.WorkflowOperationResult;
import org.opencastproject.workspace.api.Workspace;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.util.UUID;

@Component(
    immediate = true,
    service = WorkflowOperationHandler.class,
    property = {
        "service.description=Attach Whisper Transcription Workflow Operation Handler",
        "workflow.operation=whisper-attach-transcription"
    }
)
public class WhisperAttachTranscriptionOperationHandler extends AbstractWorkflowOperationHandler {

  /** The logging facility */
  private static final Logger logger = LoggerFactory.getLogger(WhisperAttachTranscriptionOperationHandler.class);

  /** Workflow configuration option keys */
  private static final String TRANSCRIPT_COLLECTION = "whisper-transcripts";
  static final String TRANSCRIPTION_JOB_ID = "transcription-job-id";
  static final String TARGET_CAPTION_FORMAT = "target-caption-format";
  static final String TARGET_TYPE = "target-element-type";

  /** The transcription service */
  private TranscriptionService service = null;
  private Workspace workspace;
  // protected WorkingFileRepository wfr;
  private CaptionService captionService;

  @Override
  @Activate
  protected void activate(ComponentContext cc) {
    super.activate(cc);
    logger.info("Registering Whisper Attach Transcription workflow operation handler");
  }

  @Override
  public WorkflowOperationResult start(WorkflowInstance workflowInstance, JobContext context)
          throws WorkflowOperationException {
    MediaPackage mediaPackage = workflowInstance.getMediaPackage();
    WorkflowOperationInstance operation = workflowInstance.getCurrentOperation();
    String mpId = mediaPackage.getIdentifier().toString();
    String language = operation.getConfiguration("language");
    String targetTag = operation.getConfiguration("target-tag");
    String targetFlavor = operation.getConfiguration("target-flavor");
    String filename = "captions-" + mpId + ".vtt";

    logger.info("Starting Attach operation for mediapackage {}", mediaPackage);
    logger.info("current operation: {}", operation);

    // Results already saved?
    URI uri = workspace.getCollectionURI(TRANSCRIPT_COLLECTION, filename);
    logger.info("Looking for transcript at URI: {}", uri);

    // URI vttUri = workspace.getURI(mpId, filename);
    // logger.info("VTT url: {}", vttUri);

    // String vttUriStr = workflowInstance.getConfiguration("whisper.vtt.uri");
    // if (StringUtils.isBlank(vttUriStr)) {
    //   throw new WorkflowOperationException("No VTT URI found in workflow configuration (whisper.vtt.uri)");
    // }

    try {
      // URI vttUri = new URI(vttUriStr);
      // workspace.get(uri);
      // logger.info("Found captions at URI: {}", uri);

      MediaPackageElementBuilder builder = MediaPackageElementBuilderFactory.newInstance().newElementBuilder();

      Track transcription = (Track) builder.newElement(Track.TYPE,
          MediaPackageElementFlavor.parseFlavor(targetFlavor + "+" + language)
      );

      transcription.setIdentifier(UUID.randomUUID().toString());
      transcription.setMimeType(MimeType.mimeType("text", "vtt"));

      try (InputStream vttStream = workspace.read(uri)) {

        URI storedUri = workspace.put(
            mediaPackage.getIdentifier().toString(),
            transcription.getIdentifier(),
            filename,
            vttStream
        );

        transcription.setURI(storedUri);
      } catch (NotFoundException e) {
        throw new WorkflowOperationException("Transcript file not found in workspace: " + uri, e);
      }

      mediaPackage.add(transcription);

      logger.info("Whisper captions attached as Track {} to MediaPackage {}",
          transcription.getIdentifier(),
          mediaPackage.getIdentifier());

      return createResult(mediaPackage, WorkflowOperationResult.Action.CONTINUE);
    } catch (Exception e) {
      throw new WorkflowOperationException("Failed to attach Whisper transcription", e);
    }


    // // Get job id.
    // String jobId = StringUtils.trimToNull(operation.getConfiguration(TRANSCRIPTION_JOB_ID));
    // if (jobId == null) {
    //   throw new WorkflowOperationException(TRANSCRIPTION_JOB_ID + " missing");
    // }

    // // Check which tags/flavors have been configured
    // ConfiguredTagsAndFlavors tagsAndFlavors = getTagsAndFlavors(
    //     workflowInstance, Configuration.none, Configuration.none, Configuration.many, Configuration.one);
    // ConfiguredTagsAndFlavors.TargetTags targetTagOption = tagsAndFlavors.getTargetTags();
    // MediaPackageElementFlavor targetFlavor = tagsAndFlavors.getSingleTargetFlavor();
    // String captionFormatOption = StringUtils.trimToNull(operation.getConfiguration(TARGET_CAPTION_FORMAT));
    // String typeUnparsed = StringUtils.trimToEmpty(operation.getConfiguration(TARGET_TYPE));
    // MediaPackageElement.Type type = null;
    // if (!typeUnparsed.isEmpty()) {
    //   // Case insensitive matching between user input (workflow config key) and enum value
    //   for (MediaPackageElement.Type t : MediaPackageElement.Type.values()) {
    //     if (t.name().equalsIgnoreCase(typeUnparsed)) {
    //       type = t;
    //     }
    //   }
    //   if (type == null || (type != Track.TYPE && type != Attachment.TYPE)) {
    //     throw new IllegalArgumentException(String.format("The given type '%s' 
    // for mediapackage %s was illegal. Please"
    //             + "check the operations' configuration keys.", type, mediaPackage.getIdentifier()));
    //   }
    // } else {
    //   type = Track.TYPE;
    // }

    // try {
    //   // Get transcription file from the service
    //   MediaPackageElement original = service.getGeneratedTranscription(
    // mediaPackage.getIdentifier().toString(), jobId,
    //           type);
    //   MediaPackageElement transcription = original;

    //   // If caption format passed, convert to desired format
    //   if (captionFormatOption != null) {
    //     Job job = captionService.convert(transcription, "whisper", captionFormatOption, service.getLanguage());
    //     if (!waitForStatus(job).isSuccess()) {
    //       throw new WorkflowOperationException("Transcription format conversion job did not complete 
    // successfully");
    //     }
    //     transcription = MediaPackageElementParser.getFromXml(job.getPayload());
    //   }

    //   // Set the target flavor if informed
    //   transcription.setFlavor(targetFlavor);

    //   // Add tags
    //   applyTargetTagsToElement(targetTagOption, transcription);

    //   // Add to media package
    //   mediaPackage.add(transcription);

    //   String uri = transcription.getURI().toString();
    //   String ext = uri.substring(uri.lastIndexOf("."));
    //   transcription.setURI(workspace.moveTo(transcription.getURI(), mediaPackage.getIdentifier().toString(),
    //           transcription.getIdentifier(), "captions." + ext));
    // } catch (Exception e) {
    //   throw new WorkflowOperationException(e);
    // }

    // return createResult(mediaPackage, Action.CONTINUE);
  }

  @Reference(target = "(provider=whisper)")
  public void setTranscriptionService(TranscriptionService service) {
    this.service = service;
  }

  @Reference
  public void setWorkspace(Workspace service) {
    this.workspace = service;
  }

  @Reference
  public void setCaptionService(CaptionService service) {
    this.captionService = service;
  }

  @Reference
  @Override
  public void setServiceRegistry(ServiceRegistry serviceRegistry) {
    super.setServiceRegistry(serviceRegistry);
  }

  // @Reference
  // protected void setWorkingFileRepository(WorkingFileRepository workingFileRepository) {
  //   this.wfr = wfr;
  // }

}
