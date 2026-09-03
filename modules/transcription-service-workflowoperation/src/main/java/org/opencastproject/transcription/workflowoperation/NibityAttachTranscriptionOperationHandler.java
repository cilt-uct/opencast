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
package org.opencastproject.transcription.workflowoperation;

import org.opencastproject.job.api.JobContext;
import org.opencastproject.mediapackage.Attachment;
import org.opencastproject.mediapackage.MediaPackage;
import org.opencastproject.mediapackage.MediaPackageElement;
import org.opencastproject.mediapackage.MediaPackageElementBuilder;
import org.opencastproject.mediapackage.MediaPackageElementBuilderFactory;
import org.opencastproject.mediapackage.MediaPackageElementFlavor;
import org.opencastproject.mediapackage.Track;
import org.opencastproject.serviceregistry.api.ServiceRegistry;
import org.opencastproject.transcription.api.TranscriptionService;
import org.opencastproject.util.MimeType;
import org.opencastproject.workflow.api.AbstractWorkflowOperationHandler;
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

import java.io.InputStream;
import java.net.URI;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component(
    immediate = true,
    service = WorkflowOperationHandler.class,
    property = {
        "service.description=Nibity Attach Transcription Workflow Operation Handler",
        "workflow.operation=nibity-attach-transcription"
    }
)

public class NibityAttachTranscriptionOperationHandler extends AbstractWorkflowOperationHandler {

  /** The logging facility */
  private static final Logger logger = LoggerFactory.getLogger(NibityAttachTranscriptionOperationHandler.class);

  /** Workflow configuration option keys */
  static final String TRANSCRIPTION_JOB_ID = "transcription-job-id";
  static final String TARGET_CAPTION_FORMAT = "target-caption-format";
  static final String TARGET_TYPE = "target-element-type";
  static final String HAS_VTT = "has-vtt";

  /** The transcription service */
  private TranscriptionService service = null;

  /** Workspace service */
  private Workspace workspace;

  @Override
  @Activate
  protected void activate(ComponentContext cc) {
    super.activate(cc);
    logger.info("Registering Nibity Attach Transcription workflow operation handler");
  }

  /**
   * {@inheritDoc}
   *
   * @see org.opencastproject.workflow.api.WorkflowOperationHandler
   * #start(org.opencastproject.workflow.api.WorkflowInstance, JobContext)
   */
  @Override
  public WorkflowOperationResult start(WorkflowInstance workflowInstance, JobContext context)
          throws WorkflowOperationException {
    MediaPackage mediaPackage = workflowInstance.getMediaPackage();
    WorkflowOperationInstance operation = workflowInstance.getCurrentOperation();

    logger.debug("Attach transcription for mediapackage {} started", mediaPackage);

    // Get job id.
    String jobId = StringUtils.trimToNull(operation.getConfiguration(TRANSCRIPTION_JOB_ID));
    if (jobId == null) {
      throw new WorkflowOperationException(TRANSCRIPTION_JOB_ID + " missing");
    }

    // Check which tags/flavors have been configured
    String targetFlavorOption = StringUtils.trimToNull(operation.getConfiguration(TARGET_FLAVOR));
    String targetTagOption = StringUtils.trimToNull(operation.getConfiguration(TARGET_TAG));
    String captionFormatOption = StringUtils.trimToNull(operation.getConfiguration(TARGET_CAPTION_FORMAT));
    String typeUnparsed = StringUtils.trimToEmpty(operation.getConfiguration(TARGET_TYPE));
    MediaPackageElement.Type type = null;
    if (!typeUnparsed.isEmpty()) {
      // Case insensitive matching between user input (workflow config key) and enum value
      for (MediaPackageElement.Type t : MediaPackageElement.Type.values()) {
        if (t.name().equalsIgnoreCase(typeUnparsed)) {
          type = t;
        }
      }
      if (type == null || (type != Track.TYPE && type != Attachment.TYPE)) {
        throw new IllegalArgumentException(String.format("The given type '%s' for mediapackage %s was illegal. Please"
                + "check the operations' configuration keys.", type, mediaPackage.getIdentifier()));
      }
    } else {
      type = Track.TYPE;
    }

    // Target flavor is mandatory if target-caption-format was NOT informed and no conversion is done
    if (targetFlavorOption == null && captionFormatOption == null) {
      throw new WorkflowOperationException(TARGET_FLAVOR + " missing");
    }

    // Target flavor is optional if target-caption-format was informed because the default flavor
    // will be "captions/<format>". If informed, will override the default.
    MediaPackageElementFlavor flavor = null;
    if (targetFlavorOption != null) {
      flavor = MediaPackageElementFlavor.parseFlavor(targetFlavorOption);
    }

    try {
      // Get transcription result zip file from the service
      MediaPackageElement transcription = service.
            getGeneratedTranscription(mediaPackage.getIdentifier().toString(), jobId, type);

      if (transcription == null || transcription.getURI() == null) {
        throw new WorkflowOperationException("Transcription element or URI is null for job " + jobId);
      }

      java.io.File transcriptionFile;
      try {
        transcriptionFile = workspace.get(transcription.getURI());
      } catch (org.opencastproject.util.NotFoundException e) {
        logger.warn("Transcription file not found yet in workspace for job {} (URI: {}). Triggering workflow retry.",
            jobId, transcription.getURI());
        throw new WorkflowOperationException("Transcription file not found yet in workspace", e);
      }

      try (ZipFile zipFile = new ZipFile(transcriptionFile)) {
        ZipEntry zippedVtt = findEntryByExtension(zipFile, ".vtt");
        ZipEntry zippedJson = findEntryByExtension(zipFile, ".json");

        if (zippedVtt == null && zippedJson == null) {
          logger.debug("Neither captions nor transcript found in zip file {}", transcription.getURI());
          throw new WorkflowOperationException("neither captions nor transcript found in the zip file");
        } else {
          // Extract the transcript vtt
          if (zippedVtt != null) {
            InputStream zis = zipFile.getInputStream(zippedVtt);
            String captionMimeType = "text/vtt";
            String captionIdentifier = "captions.vtt";
            String captionFileType = "vtt";
            MediaPackageElement.Type captionType = Track.TYPE;
            mediaPackage = addTranscriptionElementToMediaPackage(zis, captionMimeType, captionIdentifier,
             captionFileType, mediaPackage, flavor, captionType, targetTagOption);
          } else {
            workflowInstance.setConfiguration(HAS_VTT, "false");
          }

          // Extract the transcript json
          if (zippedJson != null) {
            InputStream zis = zipFile.getInputStream(zippedJson);
            String jsonMimeType = "application/json";
            String jsonIdentifier = "captions.json";
            String jsonFileType = "json";
            MediaPackageElementFlavor jsonFlavor = MediaPackageElementFlavor.parseFlavor("captions/json");
            MediaPackageElement.Type jsonType = Attachment.TYPE;
            mediaPackage = addTranscriptionElementToMediaPackage(zis, jsonMimeType, jsonIdentifier,
                    jsonFileType, mediaPackage, jsonFlavor, jsonType, targetTagOption);
          }

          // Add the zip file to the media package
          transcription.setIdentifier("nibity-transcript-" + jobId);
          transcription.setURI(workspace.moveTo(transcription.getURI(), mediaPackage.getIdentifier().toString(),
                  transcription.getIdentifier(), "nibity-" + jobId + ".zip"));
          mediaPackage.add(transcription);

          logger.info("Added this URI to mediapackage {}: {}", mediaPackage.getIdentifier(), transcription.getURI());
        }
      } catch (java.io.IOException e) {
        logger.warn("Error while reading transcription zip file for job {}.", jobId, e);
        throw new WorkflowOperationException("Error while reading transcription ZIP file", e);
      }
    } catch (WorkflowOperationException woe) {
      throw woe;
    } catch (Exception e) {
      throw new WorkflowOperationException(e);
    }

    return createResult(mediaPackage, Action.CONTINUE);
  }

  @Reference(target = "(provider=nibity)")
  public void setTranscriptionService(TranscriptionService service) {
    this.service = service;
  }

  private ZipEntry findEntryByExtension(ZipFile zip, String ext) {
    return zip.stream()
        .filter(e -> e.getName().toLowerCase().endsWith(ext))
        .findFirst()
        .orElse(null);
  }

  @Reference
  public void setWorkspace(Workspace service) {
    this.workspace = service;
  }

  @Reference
  @Override
  public void setServiceRegistry(ServiceRegistry serviceRegistry) {
    super.setServiceRegistry(serviceRegistry);
  }

  public MediaPackage addTranscriptionElementToMediaPackage(InputStream zis, String captionMimeType,
                                                    String captionIdentifier, String captionFileType,
                                                    MediaPackage mediaPackage, MediaPackageElementFlavor flavor,
                                                    MediaPackageElement.Type captionType, String targetTagOption)
          throws WorkflowOperationException {
    try {
      MediaPackageElementBuilder builder = MediaPackageElementBuilderFactory.newInstance().newElementBuilder();
      MediaPackageElement transcriptElement = builder.newElement(captionType,
              new MediaPackageElementFlavor("captions", captionFileType));
      transcriptElement.setIdentifier(UUID.randomUUID().toString());
      transcriptElement.setMimeType(
              MimeType.mimeType(captionMimeType.substring(0, captionMimeType.indexOf("/")),
                      captionMimeType.substring(captionMimeType.indexOf("/") + 1)));
      URI transcriptURI = workspace.put(mediaPackage.getIdentifier().toString(), transcriptElement.getIdentifier(),
              captionIdentifier, zis);
      transcriptElement.setURI(transcriptURI);
      mediaPackage.add(transcriptElement);

      // Set the target flavor if informed
      if (flavor != null) {
        transcriptElement.setFlavor(flavor);
      }

      // Add tags
      if (targetTagOption != null) {
        for (String tag : asList(targetTagOption)) {
          if (StringUtils.trimToNull(tag) != null) {
            transcriptElement.addTag(tag);
          }
        }
      }
    } catch (Exception e) {
      throw new WorkflowOperationException(e);
    }

    return mediaPackage;
  }
}
