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

package org.opencastproject.workflow.handler.composer;

import org.opencastproject.composer.api.ComposerService;
import org.opencastproject.composer.api.EncoderException;
import org.opencastproject.composer.api.EncodingProfile;
import org.opencastproject.job.api.Job;
import org.opencastproject.job.api.JobContext;
import org.opencastproject.mediapackage.MediaPackage;
import org.opencastproject.mediapackage.MediaPackageElementFlavor;
import org.opencastproject.mediapackage.MediaPackageElementParser;
import org.opencastproject.mediapackage.MediaPackageException;
import org.opencastproject.mediapackage.Track;
import org.opencastproject.mediapackage.selector.AbstractMediaPackageElementSelector;
import org.opencastproject.mediapackage.selector.TrackSelector;
import org.opencastproject.serviceregistry.api.ServiceRegistry;
import org.opencastproject.util.NotFoundException;
import org.opencastproject.workflow.api.AbstractWorkflowOperationHandler;
import org.opencastproject.workflow.api.ConfiguredTagsAndFlavors;
import org.opencastproject.workflow.api.WorkflowInstance;
import org.opencastproject.workflow.api.WorkflowOperationException;
import org.opencastproject.workflow.api.WorkflowOperationHandler;
import org.opencastproject.workflow.api.WorkflowOperationInstance;
import org.opencastproject.workflow.api.WorkflowOperationResult;
import org.opencastproject.workflow.api.WorkflowOperationResult.Action;
import org.opencastproject.workspace.api.Workspace;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The workflow definition for handling "mux" operations
 */
@Component(
    immediate = true,
    service = WorkflowOperationHandler.class,
    property = {
        "service.description=Mux Workflow Operation Handler",
        "workflow.operation=mux"
    }
)
public class MuxWorkflowOperationHandler extends AbstractWorkflowOperationHandler {

  /**
   * Configuration key for the mux operation to run in for-each mode. If set to true, the mux operation will be executed
   * for each main source track. If set to false, the mux operation will be executed once with all main source tracks as
   * inputs.
   */
  private static final String OPT_FOR_EACH = "for-each";

  /**
   * Default value for the for-each configuration.
   */
  private static final String DEFAULT_FOR_EACH = "false";

  /** The logging facility */
  private static final Logger logger = LoggerFactory.getLogger(MuxWorkflowOperationHandler.class);

  /** The composer service */
  private ComposerService composerService = null;

  /**
   * Callback for the OSGi declarative services configuration.
   *
   * @param composerService
   *          the local composer service
   */
  @Reference
  protected void setComposerService(ComposerService composerService) {
    this.composerService = composerService;
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

  /**
   * {@inheritDoc}
   *
   * @see WorkflowOperationHandler#start(WorkflowInstance,
   *      JobContext)
   */
  public WorkflowOperationResult start(final WorkflowInstance workflowInstance, JobContext context)
          throws WorkflowOperationException {
    logger.debug("Running mux workflow operation on workflow {}", workflowInstance.getId());

    try {
      return mux(workflowInstance);
    } catch (Exception e) {
      throw new WorkflowOperationException(e);
    }
  }

  /**
   * Mux tracks from MediaPackage using profiles stored in properties and updates current MediaPackage.
   *
   * @param workflowInstance
   *          the current workflow instance
   * @return the operation result containing the updated media package
   * @throws EncoderException
   *           if encoding fails
   * @throws WorkflowOperationException
   *           if errors occur during processing
   * @throws IOException
   *           if the workspace operations fail
   * @throws NotFoundException
   *           if the workspace doesn't contain the requested file
   */
  private WorkflowOperationResult mux(WorkflowInstance workflowInstance)
          throws EncoderException, IOException, NotFoundException, MediaPackageException, WorkflowOperationException {
    MediaPackage src = workflowInstance.getMediaPackage();
    MediaPackage mediaPackage = (MediaPackage) src.clone();
    WorkflowOperationInstance operation = workflowInstance.getCurrentOperation();
    // Check which tags have been configured
    ConfiguredTagsAndFlavors tagsAndFlavors = getTagsAndFlavors(workflowInstance,
        Configuration.many, Configuration.many, Configuration.many, Configuration.many);
    ConfiguredTagsAndFlavors.TargetTags targetTagsOption = tagsAndFlavors.getTargetTags();
    MediaPackageElementFlavor targetFlavor = tagsAndFlavors.getSingleTargetFlavor();

    AbstractMediaPackageElementSelector<Track> elementSelector = new TrackSelector();

    Map<String, List<Track>> inputTracks = new HashMap<>();
    for (MediaPackageElementFlavor srcFlavor : tagsAndFlavors.getSrcFlavors()) {
      elementSelector.addFlavor(srcFlavor);
    }
    for (String srcTag : tagsAndFlavors.getSrcTags()) {
      elementSelector.addTag(srcTag);
    }
    Collection<Track> srcTracks = elementSelector.select(mediaPackage, false);
    inputTracks.put("video", srcTracks.stream().toList());

    // handle mux woh specific input keys
    final String sourceFlavorPrefix = "source-flavor-";
    final String sourceFlavorsPrefix = "source-flavors-";
    final String sourceTagPrefix = "source-tag-";
    final String sourceTagsPrefix = "source-tags-";
    for (String flavorConfKey : operation.getConfigurationKeys().stream().filter(
        confKey -> StringUtils.startsWith(confKey, sourceFlavorPrefix) || StringUtils.startsWith(confKey,
            sourceFlavorsPrefix)).collect(Collectors.toSet())) {
      elementSelector = new TrackSelector();
      for (String srcFlavor : StringUtils.split(operation.getConfiguration(flavorConfKey), ",")) {
        elementSelector.addFlavor(srcFlavor);
      }
      String srcType = StringUtils.split(flavorConfKey, "-", 3)[2];
      for (String srcTag : operation.getConfigurationKeys().stream().filter(
          confKey -> StringUtils.equals(confKey, sourceTagPrefix + srcType)
              || StringUtils.equals(confKey, sourceTagsPrefix + srcType)).collect(Collectors.toSet())) {
        asList(StringUtils.trimToNull(srcTag)).stream().filter(Objects::nonNull).forEach(elementSelector::addTag);
      }
      srcTracks = elementSelector.select(mediaPackage, false);
      if (!srcTracks.isEmpty()) {
        if (inputTracks.containsKey(srcType)) {
          inputTracks.get(srcType).addAll(srcTracks);
        } else {
          inputTracks.put(srcType, new ArrayList<>(srcTracks));
        }
      }
    }
    // Handle tags-only inputs
    for (String tagConfKey : operation.getConfigurationKeys().stream().filter(
        confKey -> StringUtils.startsWith(confKey, sourceTagPrefix) || StringUtils.startsWith(confKey,
            sourceTagsPrefix)).collect(Collectors.toSet())) {
      String srcType = StringUtils.split(tagConfKey, "-", 3)[2];
      if (inputTracks.containsKey(srcType)) {
        continue;
      }
      elementSelector = new TrackSelector();
      asList(StringUtils.trimToNull(operation.getConfiguration(tagConfKey)))
          .stream().filter(Objects::nonNull).forEach(elementSelector::addTag);
      srcTracks = elementSelector.select(mediaPackage, false);
      inputTracks.put(srcType, new ArrayList<>(srcTracks));
    }

    if (inputTracks.isEmpty()) {
      logger.warn("No matching tracks found");
      return createResult(mediaPackage, Action.SKIP);
    }
    boolean forEach = BooleanUtils.toBoolean(getConfig(workflowInstance, OPT_FOR_EACH, DEFAULT_FOR_EACH));
    if (forEach && inputTracks.get("video").isEmpty()) {
      logger.warn("No matching main tracks found with active for-each mode");
      return createResult(mediaPackage, Action.SKIP);
    }

    String profileId = StringUtils.trimToNull(operation.getConfiguration("encoding-profile"));
    if (profileId == null) {
      throw new WorkflowOperationException("No encoding profile was specified");
    }
    EncodingProfile profile = composerService.getProfile(profileId);
    if (profile == null) {
      throw new WorkflowOperationException("Encoding profile '" + profileId + "' was not found");
    }

    List<Map<String, Track>> muxSourceTracksMaps = new ArrayList<>();
    if (forEach) {
      Set<Map.Entry<String, List<Track>>> inputTracksWithoutVideo = inputTracks.entrySet().stream()
          .filter(srcType -> !srcType.getKey().equals("video"))
          .collect(Collectors.toSet());
      inputTracks.get("video").forEach(videoTrack -> {
        Map<String, Track> muxSourceTracksMap = new HashMap<>();
        muxSourceTracksMap.put("video.1", videoTrack);
        for (Map.Entry<String, List<Track>> srcType : inputTracksWithoutVideo) {
          List<Track> srcTypeTracks = srcType.getValue();
          for (int i = 0; i < srcTypeTracks.size(); i++) {
            muxSourceTracksMap.put(String.format("%s.%d", srcType.getKey(), i + 1), srcTypeTracks.get(i));
          }
        }
        muxSourceTracksMaps.add(muxSourceTracksMap);
      });
    } else {
      Map<String, Track> muxSourceTracksMap  = new HashMap<>();
      for (String srcType : inputTracks.keySet()) {
        List<Track> srcTypeTracks = inputTracks.get(srcType);
        for (int i = 0; i < srcTypeTracks.size(); i++) {
          muxSourceTracksMap.put(String.format("%s.%d", srcType, i + 1), srcTypeTracks.get(i));
        }
      }
      muxSourceTracksMaps.add(muxSourceTracksMap);
    }

    List<Job> muxJobs = new ArrayList<>(muxSourceTracksMaps.size());
    for (Map<String, Track> muxSourceTracksMap : muxSourceTracksMaps) {
      Job muxJob = composerService.mux(muxSourceTracksMap, profileId);
      muxJobs.add(muxJob);
    }

    // Wait for the jobs to return
    if (!waitForStatus(muxJobs.toArray(new Job[0])).isSuccess()) {
      throw new WorkflowOperationException("Mux jobs did not complete successfully");
    }

    for (int i = 0; i < muxJobs.size(); i++) {
      Job muxJob = muxJobs.get(i);
      Track encodedTrack = (Track) MediaPackageElementParser.getFromXml(muxJob.getPayload());

      MediaPackageElementFlavor encodedFlavor = targetFlavor;
      if (forEach) {
        Track inputTrack = inputTracks.get("video").get(i);
        inputTrack.setTags(inputTrack.getTags());
        if ("*".equals(encodedFlavor.getType())) {
          encodedFlavor = new MediaPackageElementFlavor(inputTrack.getFlavor().getType(), encodedFlavor.getSubtype());
        }
        if ("*".equals(encodedFlavor.getSubtype())) {
          encodedFlavor = new MediaPackageElementFlavor(encodedFlavor.getType(), inputTrack.getFlavor().getSubtype());
        }
      }
      encodedTrack.setFlavor(encodedFlavor);
      applyTargetTagsToElement(targetTagsOption, encodedTrack);

      // store new track to mediaPackage
      String fileName = getFileNameFromElements(encodedTrack, encodedTrack);
      encodedTrack.setURI(workspace.moveTo(encodedTrack.getURI(), mediaPackage.getIdentifier().toString(),
          encodedTrack.getIdentifier(), fileName));
      if (forEach) {
        mediaPackage.addDerived(encodedTrack, inputTracks.get("video").get(i));
      } else if (muxSourceTracksMaps.get(i).size() == 1) {
        mediaPackage.addDerived(encodedTrack, muxSourceTracksMaps.get(i).values().iterator().next());
      } else {
        mediaPackage.add(encodedTrack);
      }
    }

    WorkflowOperationResult result = createResult(mediaPackage, Action.CONTINUE);
    logger.debug("Mux operation completed");
    return result;
  }

  @Reference
  @Override
  public void setServiceRegistry(ServiceRegistry serviceRegistry) {
    super.setServiceRegistry(serviceRegistry);
  }
}
