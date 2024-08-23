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

package org.opencastproject.workflow.handler.workflow;


import org.opencastproject.assetmanager.api.AssetManager;
import org.opencastproject.distribution.api.DistributionService;
import org.opencastproject.security.api.AuthorizationService;
import org.opencastproject.series.api.SeriesService;
import org.opencastproject.serviceregistry.api.ServiceRegistry;
import org.opencastproject.workflow.api.WorkflowOperationHandler;
import org.opencastproject.workspace.api.Workspace;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * This WOH duplicates an input event for AWS S3.
 */
@Component(
    immediate = true,
    name = "org.opencastproject.workflow.handler.workflow.DuplicateEventAWSS3WorkflowOperationHandler",
    service = WorkflowOperationHandler.class,
    property = {
        "service.description=Duplicate Event Workflow Handler",
        "workflow.operation=duplicate-event-aws"
    }
)
public class DuplicateEventAWSS3WorkflowOperationHandler extends DuplicateEventWorkflowOperationHandler {
  @Reference
  @Override
  public void setAuthorizationService(AuthorizationService authorizationService) {
    super.setAuthorizationService(authorizationService);
  }

  @Reference
  @Override
  public void setSeriesService(SeriesService seriesService) {
    super.setSeriesService(seriesService);
  }

  @Reference
  @Override
  public void setAssetManager(AssetManager assetManager) {
    super.setAssetManager(assetManager);
  }

  @Reference
  @Override
  public void setWorkspace(Workspace workspace) {
    super.setWorkspace(workspace);
  }

  @Reference(target = "(distribution.channel=aws.s3)")
  @Override
  public void setDistributionService(DistributionService distributionService) {
    super.setDistributionService(distributionService);
  }

  @Reference
  @Override
  public void setServiceRegistry(ServiceRegistry serviceRegistry) {
    super.setServiceRegistry(serviceRegistry);
  }
}
