/*
 *  Copyright 2021 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.catalog.jdbi3;

import static org.openmetadata.catalog.Entity.FIELD_EXTENSION;
import static org.openmetadata.catalog.Entity.FIELD_FOLLOWERS;
import static org.openmetadata.catalog.Entity.FIELD_OWNER;
import static org.openmetadata.catalog.Entity.FIELD_TAGS;
import static org.openmetadata.catalog.Entity.PIPELINE_SERVICE;
import static org.openmetadata.catalog.util.EntityUtil.taskMatch;
import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;
import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.openmetadata.catalog.Entity;
import org.openmetadata.catalog.entity.data.Pipeline;
import org.openmetadata.catalog.entity.data.PipelineStatus;
import org.openmetadata.catalog.entity.services.PipelineService;
import org.openmetadata.catalog.exception.CatalogExceptionMessage;
import org.openmetadata.catalog.exception.EntityNotFoundException;
import org.openmetadata.catalog.resources.pipelines.PipelineResource;
import org.openmetadata.catalog.type.EntityReference;
import org.openmetadata.catalog.type.Relationship;
import org.openmetadata.catalog.type.Status;
import org.openmetadata.catalog.type.TagLabel;
import org.openmetadata.catalog.type.Task;
import org.openmetadata.catalog.util.EntityUtil;
import org.openmetadata.catalog.util.EntityUtil.Fields;
import org.openmetadata.catalog.util.FullyQualifiedName;
import org.openmetadata.catalog.util.JsonUtils;
import org.openmetadata.catalog.util.RestUtil;
import org.openmetadata.catalog.util.ResultList;

public class PipelineRepository extends EntityRepository<Pipeline> {
  private static final String PIPELINE_UPDATE_FIELDS = "owner,tags,tasks,extension";
  private static final String PIPELINE_PATCH_FIELDS = "owner,tags,tasks,extension";
  public static final String PIPELINE_STATUS_EXTENSION = "pipeline.pipelineStatus";

  public PipelineRepository(CollectionDAO dao) {
    super(
        PipelineResource.COLLECTION_PATH,
        Entity.PIPELINE,
        Pipeline.class,
        dao.pipelineDAO(),
        dao,
        PIPELINE_PATCH_FIELDS,
        PIPELINE_UPDATE_FIELDS);
  }

  @Override
  public void setFullyQualifiedName(Pipeline pipeline) {
    pipeline.setFullyQualifiedName(FullyQualifiedName.add(pipeline.getService().getName(), pipeline.getName()));
  }

  @Override
  public Pipeline setFields(Pipeline pipeline, Fields fields) throws IOException {
    pipeline.setDisplayName(pipeline.getDisplayName());
    pipeline.setService(getContainer(pipeline.getId()));
    pipeline.setPipelineUrl(pipeline.getPipelineUrl());
    pipeline.setStartDate(pipeline.getStartDate());
    pipeline.setConcurrency(pipeline.getConcurrency());
    pipeline.setOwner(fields.contains(FIELD_OWNER) ? getOwner(pipeline) : null);
    pipeline.setFollowers(fields.contains(FIELD_FOLLOWERS) ? getFollowers(pipeline) : null);
    if (!fields.contains("tasks")) {
      pipeline.withTasks(null);
    }
    pipeline.setPipelineStatus(fields.contains("pipelineStatus") ? getPipelineStatus(pipeline) : null);
    pipeline.setTags(fields.contains(FIELD_TAGS) ? getTags(pipeline.getFullyQualifiedName()) : null);
    pipeline.setExtension(fields.contains(FIELD_EXTENSION) ? getExtension(pipeline) : null);
    return pipeline;
  }

  private PipelineStatus getPipelineStatus(Pipeline pipeline) throws IOException {
    return JsonUtils.readValue(
        daoCollection
            .entityExtensionTimeSeriesDao()
            .getLatestExtension(pipeline.getId().toString(), PIPELINE_STATUS_EXTENSION),
        PipelineStatus.class);
  }

  @Transaction
  public Pipeline addPipelineStatus(UUID pipelineId, PipelineStatus pipelineStatus) throws IOException {
    // Validate the request content
    Pipeline pipeline = daoCollection.pipelineDAO().findEntityById(pipelineId);
    // validate all the Tasks
    for (Status taskStatus : pipelineStatus.getTaskStatus()) {
      validateTask(pipeline, taskStatus.getName());
    }

    PipelineStatus storedPipelineStatus =
        JsonUtils.readValue(
            daoCollection
                .entityExtensionTimeSeriesDao()
                .getExtensionAtTimestamp(
                    pipelineId.toString(), PIPELINE_STATUS_EXTENSION, pipelineStatus.getTimestamp()),
            PipelineStatus.class);
    if (storedPipelineStatus != null) {
      daoCollection
          .entityExtensionTimeSeriesDao()
          .update(
              pipelineId.toString(),
              PIPELINE_STATUS_EXTENSION,
              JsonUtils.pojoToJson(pipelineStatus),
              pipelineStatus.getTimestamp());
    } else {
      daoCollection
          .entityExtensionTimeSeriesDao()
          .insert(
              pipelineId.toString(),
              pipeline.getFullyQualifiedName(),
              PIPELINE_STATUS_EXTENSION,
              "pipelineStatus",
              JsonUtils.pojoToJson(pipelineStatus));
      setFields(pipeline, EntityUtil.Fields.EMPTY_FIELDS);
    }
    return pipeline.withPipelineStatus(pipelineStatus);
  }

  @Transaction
  public Pipeline deletePipelineStatus(UUID pipelineId, Long timestamp) throws IOException {
    // Validate the request content
    Pipeline pipeline = dao.findEntityById(pipelineId);
    PipelineStatus storedPipelineStatus =
        JsonUtils.readValue(
            daoCollection
                .entityExtensionTimeSeriesDao()
                .getExtensionAtTimestamp(pipelineId.toString(), PIPELINE_STATUS_EXTENSION, timestamp),
            PipelineStatus.class);
    if (storedPipelineStatus != null) {
      daoCollection
          .entityExtensionTimeSeriesDao()
          .deleteAtTimestamp(pipelineId.toString(), PIPELINE_STATUS_EXTENSION, timestamp);
      pipeline.setPipelineStatus(storedPipelineStatus);
      return pipeline;
    }
    throw new EntityNotFoundException(
        String.format("Failed to find pipeline status for %s at %s", pipeline.getName(), timestamp));
  }

  public ResultList<PipelineStatus> getPipelineStatuses(ListFilter filter, String before, String after, int limit)
      throws IOException {
    List<PipelineStatus> pipelineStatuses;
    int total;
    // Here timestamp is used for page marker since table profiles are sorted by timestamp
    long time = Long.MAX_VALUE;

    if (before != null) { // Reverse paging
      time = Long.parseLong(RestUtil.decodeCursor(before));
      pipelineStatuses =
          JsonUtils.readObjects(
              daoCollection.entityExtensionTimeSeriesDao().listBefore(filter, limit + 1, time), PipelineStatus.class);
    } else { // Forward paging or first page
      if (after != null) {
        time = Long.parseLong(RestUtil.decodeCursor(after));
      }
      pipelineStatuses =
          JsonUtils.readObjects(
              daoCollection.entityExtensionTimeSeriesDao().listAfter(filter, limit + 1, time), PipelineStatus.class);
    }
    total = daoCollection.entityExtensionTimeSeriesDao().listCount(filter);
    String beforeCursor = null;
    String afterCursor = null;
    if (before != null) {
      if (pipelineStatuses.size() > limit) { // If extra result exists, then previous page exists - return before cursor
        pipelineStatuses.remove(0);
        beforeCursor = pipelineStatuses.get(0).getTimestamp().toString();
      }
      afterCursor = pipelineStatuses.get(pipelineStatuses.size() - 1).getTimestamp().toString();
    } else {
      beforeCursor = after == null ? null : pipelineStatuses.get(0).getTimestamp().toString();
      if (pipelineStatuses.size() > limit) { // If extra result exists, then next page exists - return after cursor
        pipelineStatuses.remove(limit);
        afterCursor = pipelineStatuses.get(limit - 1).getTimestamp().toString();
      }
    }
    return new ResultList<>(pipelineStatuses, beforeCursor, afterCursor, total);
  }

  // Validate if a given task exists in the pipeline
  private void validateTask(Pipeline pipeline, String taskName) {
    boolean validTask = pipeline.getTasks().stream().anyMatch(task -> task.getName().equals(taskName));
    if (!validTask) {
      throw new IllegalArgumentException("Invalid task name " + taskName);
    }
  }

  @Override
  public void restorePatchAttributes(Pipeline original, Pipeline updated) {
    // Patch can't make changes to following fields. Ignore the changes
    updated
        .withFullyQualifiedName(original.getFullyQualifiedName())
        .withName(original.getName())
        .withService(original.getService())
        .withId(original.getId());
  }

  @Override
  public void prepare(Pipeline pipeline) throws IOException {
    populateService(pipeline);
    setFullyQualifiedName(pipeline);
    populateOwner(pipeline.getOwner()); // Validate owner
    pipeline.setTags(addDerivedTags(pipeline.getTags()));
  }

  @Override
  public void storeEntity(Pipeline pipeline, boolean update) throws IOException {
    // Relationships and fields such as href are derived and not stored as part of json
    EntityReference owner = pipeline.getOwner();
    List<TagLabel> tags = pipeline.getTags();
    EntityReference service = pipeline.getService();

    // Don't store owner, database, href and tags as JSON. Build it on the fly based on relationships
    pipeline.withOwner(null).withService(null).withHref(null).withTags(null);

    store(pipeline.getId(), pipeline, update);

    // Restore the relationships
    pipeline.withOwner(owner).withService(service).withTags(tags);
  }

  @Override
  public void storeRelationships(Pipeline pipeline) {
    EntityReference service = pipeline.getService();
    addRelationship(service.getId(), pipeline.getId(), service.getType(), Entity.PIPELINE, Relationship.CONTAINS);

    // Add owner relationship
    storeOwner(pipeline, pipeline.getOwner());

    // Add tag to pipeline relationship
    applyTags(pipeline);
  }

  @Override
  public EntityUpdater getUpdater(Pipeline original, Pipeline updated, Operation operation) {
    return new PipelineUpdater(original, updated, operation);
  }

  private void populateService(Pipeline pipeline) throws IOException {
    PipelineService service = getService(pipeline.getService().getId(), pipeline.getService().getType());
    pipeline.setService(service.getEntityReference());
    pipeline.setServiceType(service.getServiceType());
  }

  private PipelineService getService(UUID serviceId, String entityType) throws IOException {
    if (entityType.equalsIgnoreCase(Entity.PIPELINE_SERVICE)) {
      return daoCollection.pipelineServiceDAO().findEntityById(serviceId);
    }
    throw new IllegalArgumentException(
        CatalogExceptionMessage.invalidServiceEntity(entityType, Entity.PIPELINE, PIPELINE_SERVICE));
  }

  /** Handles entity updated from PUT and POST operation. */
  public class PipelineUpdater extends EntityUpdater {
    public PipelineUpdater(Pipeline original, Pipeline updated, Operation operation) {
      super(original, updated, operation);
    }

    @Override
    public void entitySpecificUpdate() throws IOException {
      updateTasks(original, updated);
      recordChange("pipelineUrl", original.getPipelineUrl(), updated.getPipelineUrl());
      recordChange("concurrency", original.getConcurrency(), updated.getConcurrency());
      recordChange("pipelineLocation", original.getPipelineLocation(), updated.getPipelineLocation());
    }

    private void updateTasks(Pipeline original, Pipeline updated) throws JsonProcessingException {
      // While the Airflow lineage only gets executed for one Task at a time, we will consider the
      // client Task information as the source of truth. This means that at each update, we will
      // expect to receive all the tasks known until that point.

      // The lineage backend will take care of controlling new & deleted tasks, while passing to the
      // API the full list of Tasks to consider for a given Pipeline. Having a single point of control
      // of the Tasks and their status, simplifies the logic on how to add/delete tasks.

      // The API will only take care of marking tasks as added/updated/deleted based on the original
      // and incoming changes.

      List<Task> updatedTasks = listOrEmpty(updated.getTasks());
      List<Task> origTasks = listOrEmpty(original.getTasks());

      boolean newTasks = false;
      // Update the task descriptions
      for (Task updatedTask : updatedTasks) {
        Task stored = origTasks.stream().filter(c -> taskMatch.test(c, updatedTask)).findAny().orElse(null);
        if (stored == null || updatedTask == null) { // New task added
          newTasks = true;
          continue;
        }
        updateTaskDescription(stored, updatedTask);
      }

      boolean removedTasks = updatedTasks.size() < origTasks.size();

      if (newTasks || removedTasks) {
        List<Task> added = new ArrayList<>();
        List<Task> deleted = new ArrayList<>();
        recordListChange("tasks", origTasks, updatedTasks, added, deleted, taskMatch);
      }
    }

    private void updateTaskDescription(Task origTask, Task updatedTask) throws JsonProcessingException {
      if (operation.isPut() && !nullOrEmpty(origTask.getDescription())) {
        // Update description only when stored is empty to retain user authored descriptions
        updatedTask.setDescription(origTask.getDescription());
        return;
      }
      // Don't record a change if descriptions are the same
      if (origTask != null
          && ((origTask.getDescription() != null && !origTask.getDescription().equals(updatedTask.getDescription()))
              || updatedTask.getDescription() != null)) {
        recordChange(
            "tasks." + origTask.getName() + ".description", origTask.getDescription(), updatedTask.getDescription());
      }
    }
  }
}
