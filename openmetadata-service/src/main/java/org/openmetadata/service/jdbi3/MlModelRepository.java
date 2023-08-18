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

package org.openmetadata.service.jdbi3;

import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;
import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;
import static org.openmetadata.schema.type.Include.ALL;
import static org.openmetadata.service.Entity.DASHBOARD;
import static org.openmetadata.service.Entity.MLMODEL;
import static org.openmetadata.service.Entity.MLMODEL_SERVICE;
import static org.openmetadata.service.util.EntityUtil.entityReferenceMatch;
import static org.openmetadata.service.util.EntityUtil.mlFeatureMatch;
import static org.openmetadata.service.util.EntityUtil.mlHyperParameterMatch;

import java.util.ArrayList;
import java.util.List;
import javax.json.JsonPatch;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.entity.data.MlModel;
import org.openmetadata.schema.entity.services.MlModelService;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.MlFeature;
import org.openmetadata.schema.type.MlFeatureSource;
import org.openmetadata.schema.type.MlHyperParameter;
import org.openmetadata.schema.type.Relationship;
import org.openmetadata.schema.type.TagLabel;
import org.openmetadata.schema.type.TaskDetails;
import org.openmetadata.service.Entity;
import org.openmetadata.service.exception.CatalogExceptionMessage;
import org.openmetadata.service.jdbi3.EntityRepository.EntityUpdater;
import org.openmetadata.service.resources.feeds.MessageParser;
import org.openmetadata.service.resources.mlmodels.MlModelResource;
import org.openmetadata.service.util.EntityUtil;
import org.openmetadata.service.util.EntityUtil.Fields;
import org.openmetadata.service.util.FullyQualifiedName;
import org.openmetadata.service.util.JsonUtils;

@Slf4j
public class MlModelRepository extends EntityRepository<MlModel> {
  private static final String MODEL_UPDATE_FIELDS = "dashboard";
  private static final String MODEL_PATCH_FIELDS = "dashboard";

  public MlModelRepository(CollectionDAO dao) {
    super(
        MlModelResource.COLLECTION_PATH,
        Entity.MLMODEL,
        MlModel.class,
        dao.mlModelDAO(),
        dao,
        MODEL_PATCH_FIELDS,
        MODEL_UPDATE_FIELDS);
  }

  @Override
  public void setFullyQualifiedName(MlModel mlModel) {
    mlModel.setFullyQualifiedName(
        FullyQualifiedName.add(mlModel.getService().getFullyQualifiedName(), mlModel.getName()));
    if (!nullOrEmpty(mlModel.getMlFeatures())) {
      setMlFeatureFQN(mlModel.getFullyQualifiedName(), mlModel.getMlFeatures());
    }
  }

  @Override
  public MlModel setFields(MlModel mlModel, Fields fields) {
    mlModel.setService(getContainer(mlModel.getId()));
    mlModel.setDashboard(fields.contains("dashboard") ? getDashboard(mlModel) : mlModel.getDashboard());
    if (mlModel.getUsageSummary() == null) {
      mlModel.withUsageSummary(
          fields.contains("usageSummary")
              ? EntityUtil.getLatestUsage(daoCollection.usageDAO(), mlModel.getId())
              : mlModel.getUsageSummary());
    }
    return mlModel;
  }

  @Override
  public MlModel clearFields(MlModel mlModel, Fields fields) {
    mlModel.setDashboard(fields.contains("dashboard") ? mlModel.getDashboard() : null);
    return mlModel.withUsageSummary(fields.contains("usageSummary") ? mlModel.getUsageSummary() : null);
  }

  @Override
  public void restorePatchAttributes(MlModel original, MlModel updated) {
    // Patch can't make changes to following fields. Ignore the changes
    updated
        .withFullyQualifiedName(original.getFullyQualifiedName())
        .withService(original.getService())
        .withName(original.getName())
        .withId(original.getId());
  }

  private void setMlFeatureSourcesFQN(List<MlFeatureSource> mlSources) {
    mlSources.forEach(
        s -> {
          if (s.getDataSource() != null) {
            s.setFullyQualifiedName(FullyQualifiedName.add(s.getDataSource().getFullyQualifiedName(), s.getName()));
          } else {
            s.setFullyQualifiedName(s.getName());
          }
        });
  }

  private void setMlFeatureFQN(String parentFQN, List<MlFeature> mlFeatures) {
    mlFeatures.forEach(
        f -> {
          String featureFqn = FullyQualifiedName.add(parentFQN, f.getName());
          f.setFullyQualifiedName(featureFqn);
          if (f.getFeatureSources() != null) {
            setMlFeatureSourcesFQN(f.getFeatureSources());
          }
        });
  }

  /** Make sure that all the MlFeatureSources are pointing to correct EntityReferences in tha Table DAO. */
  private void validateReferences(List<MlFeature> mlFeatures) {
    for (MlFeature feature : mlFeatures) {
      if (!nullOrEmpty(feature.getFeatureSources())) {
        for (MlFeatureSource source : feature.getFeatureSources()) {
          validateMlDataSource(source);
        }
      }
    }
  }

  private void validateMlDataSource(MlFeatureSource source) {
    if (source.getDataSource() != null) {
      Entity.getEntityReferenceById(
          source.getDataSource().getType(), source.getDataSource().getId(), Include.NON_DELETED);
    }
  }

  @Override
  public void prepare(MlModel mlModel) {
    populateService(mlModel);
    if (!nullOrEmpty(mlModel.getMlFeatures())) {
      validateReferences(mlModel.getMlFeatures());
      mlModel.getMlFeatures().forEach(feature -> checkMutuallyExclusive(feature.getTags()));
    }

    // Check that the dashboard exists
    if (mlModel.getDashboard() != null) {
      mlModel.setDashboard(Entity.getEntityReference(mlModel.getDashboard(), Include.NON_DELETED));
    }
  }

  @Override
  public void storeEntity(MlModel mlModel, boolean update) {
    // Relationships and fields such as service are derived and not stored as part of json
    EntityReference dashboard = mlModel.getDashboard();
    EntityReference service = mlModel.getService();
    mlModel.withService(null).withDashboard(null);
    store(mlModel, update);
    mlModel.withService(service).withDashboard(dashboard);
  }

  @Override
  public void storeRelationships(MlModel mlModel) {
    EntityReference service = mlModel.getService();
    addRelationship(service.getId(), mlModel.getId(), service.getType(), MLMODEL, Relationship.CONTAINS);

    setDashboard(mlModel, mlModel.getDashboard());

    if (mlModel.getDashboard() != null) {
      // Add relationship from MlModel --- uses ---> Dashboard
      addRelationship(
          mlModel.getId(), mlModel.getDashboard().getId(), Entity.MLMODEL, Entity.DASHBOARD, Relationship.USES);
    }

    setMlFeatureSourcesLineage(mlModel);
  }

  @Override
  public MlModel setInheritedFields(MlModel mlModel, Fields fields) {
    // If mlModel does not have domain, then inherit it from parent MLModel service
    MlModelService service = Entity.getEntity(MLMODEL_SERVICE, mlModel.getService().getId(), "domain", ALL);
    return inheritDomain(mlModel, fields, service);
  }

  /**
   * If we have the properties MLFeatures -> MlFeatureSources and the feature sources have properly informed the Data
   * Source EntityRef, then we will automatically build the lineage between tables and ML Model.
   */
  private void setMlFeatureSourcesLineage(MlModel mlModel) {
    if (mlModel.getMlFeatures() != null) {
      mlModel
          .getMlFeatures()
          .forEach(
              mlFeature -> {
                if (mlFeature.getFeatureSources() != null) {
                  mlFeature
                      .getFeatureSources()
                      .forEach(
                          mlFeatureSource -> {
                            EntityReference targetEntity = mlFeatureSource.getDataSource();
                            if (targetEntity != null) {
                              addRelationship(
                                  targetEntity.getId(),
                                  mlModel.getId(),
                                  targetEntity.getType(),
                                  MLMODEL,
                                  Relationship.UPSTREAM);
                            }
                          });
                }
              });
    }
  }

  @Override
  public EntityUpdater getUpdater(MlModel original, MlModel updated, Operation operation) {
    return new MlModelUpdater(original, updated, operation);
  }

  @Override
  public List<TagLabel> getAllTags(EntityInterface entity) {
    List<TagLabel> allTags = new ArrayList<>();
    MlModel mlModel = (MlModel) entity;
    EntityUtil.mergeTags(allTags, mlModel.getTags());
    for (MlFeature feature : listOrEmpty(mlModel.getMlFeatures())) {
      EntityUtil.mergeTags(allTags, feature.getTags());
      for (MlFeatureSource source : listOrEmpty(feature.getFeatureSources())) {
        EntityUtil.mergeTags(allTags, source.getTags());
      }
    }
    return allTags;
  }

  @Override
  public void update(TaskDetails task, MessageParser.EntityLink entityLink, String newValue, String user) {
    if (entityLink.getFieldName().equals("mlFeatures")) {
      MlModel mlModel = getByName(null, entityLink.getEntityFQN(), getFields("tags"), Include.ALL, false);
      MlFeature mlFeature =
          mlModel.getMlFeatures().stream()
              .filter(c -> c.getName().equals(entityLink.getArrayFieldName()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          CatalogExceptionMessage.invalidFieldName("chart", entityLink.getArrayFieldName())));

      String origJson = JsonUtils.pojoToJson(mlModel);
      if (EntityUtil.isDescriptionTask(task.getType())) {
        mlFeature.setDescription(newValue);
      } else if (EntityUtil.isTagTask(task.getType())) {
        List<TagLabel> tags = JsonUtils.readObjects(newValue, TagLabel.class);
        mlFeature.setTags(tags);
      }
      String updatedEntityJson = JsonUtils.pojoToJson(mlModel);
      JsonPatch patch = JsonUtils.getJsonPatch(origJson, updatedEntityJson);
      patch(null, mlModel.getId(), user, patch);
      return;
    }
    super.update(task, entityLink, newValue, user);
  }

  private void populateService(MlModel mlModel) {
    MlModelService service = Entity.getEntity(mlModel.getService(), "", Include.NON_DELETED);
    mlModel.setService(service.getEntityReference());
    mlModel.setServiceType(service.getServiceType());
  }

  private EntityReference getDashboard(MlModel mlModel) {
    return mlModel == null ? null : getToEntityRef(mlModel.getId(), Relationship.USES, DASHBOARD, false);
  }

  public void setDashboard(MlModel mlModel, EntityReference dashboard) {
    if (dashboard != null) {
      addRelationship(
          mlModel.getId(), mlModel.getDashboard().getId(), Entity.MLMODEL, Entity.DASHBOARD, Relationship.USES);
    }
  }

  /** Handles entity updated from PUT and POST operation. */
  public class MlModelUpdater extends EntityUpdater {
    public MlModelUpdater(MlModel original, MlModel updated, Operation operation) {
      super(original, updated, operation);
    }

    @Override
    public void entitySpecificUpdate() {
      updateAlgorithm(original, updated);
      updateDashboard(original, updated);
      updateMlFeatures(original, updated);
      updateMlHyperParameters(original, updated);
      updateMlStore(original, updated);
      updateServer(original, updated);
      updateTarget(original, updated);
    }

    private void updateAlgorithm(MlModel origModel, MlModel updatedModel) {
      // Updating an algorithm should be flagged for an ML Model
      if (recordChange("algorithm", origModel.getAlgorithm(), updatedModel.getAlgorithm())) {
        // Mark the EntityUpdater version change to major
        majorVersionChange = true;
      }
    }

    private void updateMlFeatures(MlModel origModel, MlModel updatedModel) {
      List<MlFeature> addedList = new ArrayList<>();
      List<MlFeature> deletedList = new ArrayList<>();
      recordListChange(
          "mlFeatures",
          origModel.getMlFeatures(),
          updatedModel.getMlFeatures(),
          addedList,
          deletedList,
          mlFeatureMatch);
    }

    private void updateMlHyperParameters(MlModel origModel, MlModel updatedModel) {
      List<MlHyperParameter> addedList = new ArrayList<>();
      List<MlHyperParameter> deletedList = new ArrayList<>();
      recordListChange(
          "mlHyperParameters",
          origModel.getMlHyperParameters(),
          updatedModel.getMlHyperParameters(),
          addedList,
          deletedList,
          mlHyperParameterMatch);
    }

    private void updateMlStore(MlModel origModel, MlModel updatedModel) {
      recordChange("mlStore", origModel.getMlStore(), updatedModel.getMlStore(), true);
    }

    private void updateServer(MlModel origModel, MlModel updatedModel) {
      // Updating the server can break current integrations to the ML services or enable new integrations
      if (recordChange("server", origModel.getServer(), updatedModel.getServer())) {
        // Mark the EntityUpdater version change to major
        majorVersionChange = true;
      }
    }

    private void updateTarget(MlModel origModel, MlModel updatedModel) {
      // Updating the target changes the model response
      if (recordChange("target", origModel.getTarget(), updatedModel.getTarget())) {
        majorVersionChange = true;
      }
    }

    private void updateDashboard(MlModel origModel, MlModel updatedModel) {
      EntityReference origDashboard = origModel.getDashboard();
      EntityReference updatedDashboard = updatedModel.getDashboard();
      if (recordChange("dashboard", origDashboard, updatedDashboard, true, entityReferenceMatch)) {

        // Remove the dashboard associated with the model, if any
        if (origModel.getDashboard() != null) {
          deleteTo(updatedModel.getId(), Entity.MLMODEL, Relationship.USES, Entity.DASHBOARD);
        }

        // Add relationship from model -- uses --> dashboard
        if (updatedDashboard != null) {
          addRelationship(
              updatedModel.getId(), updatedDashboard.getId(), Entity.MLMODEL, Entity.DASHBOARD, Relationship.USES);
        }
      }
    }
  }
}
