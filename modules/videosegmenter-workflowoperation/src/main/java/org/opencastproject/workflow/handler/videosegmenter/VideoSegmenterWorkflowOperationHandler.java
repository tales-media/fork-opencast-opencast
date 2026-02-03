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

package org.opencastproject.workflow.handler.videosegmenter;

import org.opencastproject.job.api.Job;
import org.opencastproject.job.api.JobContext;
import org.opencastproject.mediapackage.Catalog;
import org.opencastproject.mediapackage.MediaPackage;
import org.opencastproject.mediapackage.MediaPackageElementFlavor;
import org.opencastproject.mediapackage.MediaPackageElementParser;
import org.opencastproject.mediapackage.MediaPackageElements;
import org.opencastproject.mediapackage.MediaPackageReferenceImpl;
import org.opencastproject.mediapackage.Track;
import org.opencastproject.mediapackage.selector.AbstractMediaPackageElementSelector;
import org.opencastproject.mediapackage.selector.TrackSelector;
import org.opencastproject.serviceregistry.api.ServiceRegistry;
import org.opencastproject.videosegmenter.api.VideoSegmenterService;
import org.opencastproject.workflow.api.AbstractWorkflowOperationHandler;
import org.opencastproject.workflow.api.ConfiguredTagsAndFlavors;
import org.opencastproject.workflow.api.WorkflowInstance;
import org.opencastproject.workflow.api.WorkflowOperationException;
import org.opencastproject.workflow.api.WorkflowOperationHandler;
import org.opencastproject.workflow.api.WorkflowOperationInstance;
import org.opencastproject.workflow.api.WorkflowOperationResult;
import org.opencastproject.workflow.api.WorkflowOperationResult.Action;
import org.opencastproject.workspace.api.Workspace;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The workflow definition will run suitable recordings by the video segmentation.
 */
@Component(
    immediate = true,
    service = WorkflowOperationHandler.class,
    property = {
        "service.description=Videosegmentation Workflow Operation Handler",
        "workflow.operation=segment-video"
    }
)
public class VideoSegmenterWorkflowOperationHandler extends AbstractWorkflowOperationHandler {

  /** The logging facility */
  private static final Logger logger = LoggerFactory.getLogger(VideoSegmenterWorkflowOperationHandler.class);

  /** Minimum video length in seconds for video segmentation to run */
  private static final int MIN_VIDEO_LENGTH = 30000;

  /** The composer service */
  private VideoSegmenterService videosegmenter = null;

  /** The local workspace */
  private Workspace workspace = null;

  /**
   * {@inheritDoc}
   *
   * @see org.opencastproject.workflow.api.WorkflowOperationHandler#start(
   *      org.opencastproject.workflow.api.WorkflowInstance, JobContext)
   */
  @Override
  public WorkflowOperationResult start(final WorkflowInstance workflowInstance, JobContext context)
          throws WorkflowOperationException {
    logger.debug("Running video segmentation on workflow {}", workflowInstance.getId());

    WorkflowOperationInstance operation = workflowInstance.getCurrentOperation();
    MediaPackage mediaPackage = workflowInstance.getMediaPackage();

    // Find movie track to analyze
    ConfiguredTagsAndFlavors tagsAndFlavors = getTagsAndFlavors(workflowInstance,
        Configuration.many, Configuration.many, Configuration.many, Configuration.none);
    List<String> sourceTagsOption = tagsAndFlavors.getSrcTags();
    List<MediaPackageElementFlavor> sourceFlavorsOption = tagsAndFlavors.getSrcFlavors();
    ConfiguredTagsAndFlavors.TargetTags targetTags = tagsAndFlavors.getTargetTags();

    // Allow the combination of flavor and tag to narrow down choice of source
    AbstractMediaPackageElementSelector<Track> elementSelector = new TrackSelector();

    if (sourceTagsOption.isEmpty() && sourceFlavorsOption.isEmpty()) {
      // Default
      elementSelector.addFlavor(MediaPackageElements.PRESENTATION_SOURCE);
    } else {
      for (MediaPackageElementFlavor flavor : sourceFlavorsOption) {
        elementSelector.addFlavor(flavor);
      }
      for (String tag : sourceTagsOption) {
        elementSelector.addTag(tag);
      }
    }

    // Select the source flavors
    List<Track> candidates = elementSelector.select(mediaPackage, true).stream()
        .filter(t -> !t.hasVideo()) // Remove unsupported tracks (only those containing video can be segmented)
        .toList();

    // Found one?
    if (candidates.isEmpty()) {
      logger.info("No matching tracks available for video segmentation in workflow {}", workflowInstance);
      return createResult(Action.CONTINUE);
    }

    // More than one left? Let's be pragmatic...
    if (candidates.size() > 1) {
      logger.info("Found more than one track to segment, choosing the first one ({})", candidates.get(0));
    }
    Track track = candidates.get(0);

    // Skip operation if media is shorter than the minimum defined video length (30s) since we won't generate a
    // sensible segmentation on such a short video anyway.
    if (track.getDuration() != null && track.getDuration() < MIN_VIDEO_LENGTH) {
      return createResult(mediaPackage, Action.SKIP);
    }

    // Segment the media package
    Catalog mpeg7Catalog = null;
    Job job = null;
    try {
      job = videosegmenter.segment(track);
      if (!waitForStatus(job).isSuccess()) {
        throw new WorkflowOperationException("Video segmentation of " + track + " failed");
      }
      mpeg7Catalog = (Catalog) MediaPackageElementParser.getFromXml(job.getPayload());
      mediaPackage.add(mpeg7Catalog);
      mpeg7Catalog.setURI(workspace.moveTo(mpeg7Catalog.getURI(), mediaPackage.getIdentifier().toString(),
              mpeg7Catalog.getIdentifier(), "segments.xml"));
      mpeg7Catalog.setReference(new MediaPackageReferenceImpl(track));
      // Add target tags
      applyTargetTagsToElement(targetTags, mpeg7Catalog);
    } catch (Exception e) {
      throw new WorkflowOperationException(e);
    }

    logger.debug("Video segmentation completed");
    return createResult(mediaPackage, Action.CONTINUE, job.getQueueTime());
  }

  /**
   * Callback for declarative services configuration that will introduce us to the videosegmenter service.
   * Implementation assumes that the reference is configured as being static.
   *
   * @param videosegmenter
   *          the video segmenter
   */
  @Reference
  protected void setVideoSegmenter(VideoSegmenterService videosegmenter) {
    this.videosegmenter = videosegmenter;
  }

  /**
   * Callback for declarative services configuration that will introduce us to the local workspace service.
   * Implementation assumes that the reference is configured as being static.
   *
   * @param workspace
   *          an instance of the workspace
   */
  @Reference
  public void setWorkspace(Workspace workspace) {
    this.workspace = workspace;
  }

  @Reference
  @Override
  public void setServiceRegistry(ServiceRegistry serviceRegistry) {
    super.setServiceRegistry(serviceRegistry);
  }

}
