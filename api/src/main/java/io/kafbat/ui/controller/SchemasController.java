package io.kafbat.ui.controller;

import io.kafbat.ui.api.SchemasApi;
import io.kafbat.ui.exception.ValidationException;
import io.kafbat.ui.mapper.KafkaSrMapper;
import io.kafbat.ui.mapper.KafkaSrMapperImpl;
import io.kafbat.ui.model.CompatibilityCheckResponseDTO;
import io.kafbat.ui.model.CompatibilityLevelDTO;
import io.kafbat.ui.model.KafkaCluster;
import io.kafbat.ui.model.NewSchemaSubjectDTO;
import io.kafbat.ui.model.SchemaSubjectDTO;
import io.kafbat.ui.model.SchemaSubjectsResponseDTO;
import io.kafbat.ui.model.rbac.AccessContext;
import io.kafbat.ui.model.rbac.permission.SchemaAction;
import io.kafbat.ui.service.SchemaRegistryService;
import io.kafbat.ui.service.mcp.McpTool;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
@Slf4j
public class SchemasController extends AbstractController implements SchemasApi, McpTool {

  private static final Integer DEFAULT_PAGE_SIZE = 25;

  private final KafkaSrMapper kafkaSrMapper = new KafkaSrMapperImpl();

  private final SchemaRegistryService schemaRegistryService;

  @Override
  protected KafkaCluster getCluster(String clusterName) {
    var c = super.getCluster(clusterName);
    if (c.getSchemaRegistryClient() == null) {
      throw new ValidationException("Schema Registry is not set for cluster " + clusterName);
    }
    return c;
  }

  @Override
  public Mono<ResponseEntity<CompatibilityCheckResponseDTO>> checkSchemaCompatibility(
      String clusterName, String subject, @Valid Mono<NewSchemaSubjectDTO> newSchemaSubjectMono,
      ServerWebExchange exchange) {
    var context = AccessContext.builder()
        .cluster(clusterName)
        .schemaActions(subject, SchemaAction.VIEW)
        .operationName("checkSchemaCompatibility")
        .build();

    return validateAccess(context).then(
        newSchemaSubjectMono.flatMap(subjectDTO ->
                schemaRegistryService.checksSchemaCompatibility(
                    getCluster(clusterName),
                    subject,
                    kafkaSrMapper.fromDto(subjectDTO)
                ))
            .map(kafkaSrMapper::toDto)
            .map(ResponseEntity::ok)
    ).doOnEach(sig -> audit(context, sig));
  }

  @Override
  public Mono<ResponseEntity<SchemaSubjectDTO>> createNewSchema(
      String clusterName, @Valid Mono<NewSchemaSubjectDTO> newSchemaSubjectMono,
      ServerWebExchange exchange) {
    return newSchemaSubjectMono.flatMap(newSubject -> {
          var context = AccessContext.builder()
              .cluster(clusterName)
              .schemaActions(newSubject.getSubject(), SchemaAction.CREATE)
              .operationName("createNewSchema")
              .build();
          return validateAccess(context).then(
                  schemaRegistryService.registerNewSchema(
                      getCluster(clusterName),
                      newSubject.getSubject(),
                      kafkaSrMapper.fromDto(newSubject)
                  ))
              .map(kafkaSrMapper::toDto)
              .map(ResponseEntity::ok)
              .doOnEach(sig -> audit(context, sig));
        }
    );
  }

  @Override
  public Mono<ResponseEntity<Void>> deleteLatestSchema(
      String clusterName, String subject, ServerWebExchange exchange) {
    var context = AccessContext.builder()
        .cluster(clusterName)
        .schemaActions(subject, SchemaAction.DELETE)
        .operationName("deleteLatestSchema")
        .build();

    return validateAccess(context).then(
        schemaRegistryService.deleteLatestSchemaSubject(getCluster(clusterName), subject)
            .doOnEach(sig -> audit(context, sig))
            .thenReturn(ResponseEntity.ok().build())
    );
  }

  @Override
  public Mono<ResponseEntity<Void>> deleteSchema(
      String clusterName, String subject, ServerWebExchange exchange) {
    var context = AccessContext.builder()
        .cluster(clusterName)
        .schemaActions(subject, SchemaAction.DELETE)
        .operationName("deleteSchema")
        .build();

    return validateAccess(context).then(
        schemaRegistryService.deleteSchemaSubjectEntirely(getCluster(clusterName), subject)
            .doOnEach(sig -> audit(context, sig))
            .thenReturn(ResponseEntity.ok().build())
    );
  }

  @Override
  public Mono<ResponseEntity<Void>> deleteSchemaByVersion(
      String clusterName, String subjectName, Integer version, ServerWebExchange exchange) {
    var context = AccessContext.builder()
        .cluster(clusterName)
        .schemaActions(subjectName, SchemaAction.DELETE)
        .operationName("deleteSchemaByVersion")
        .build();

    return validateAccess(context).then(
        schemaRegistryService.deleteSchemaSubjectByVersion(getCluster(clusterName), subjectName, version)
            .doOnEach(sig -> audit(context, sig))
            .thenReturn(ResponseEntity.ok().build())
    );
  }

  @Override
  public Mono<ResponseEntity<Flux<SchemaSubjectDTO>>> getAllVersionsBySubject(
      String clusterName, String subjectName, ServerWebExchange exchange) {
    var context = AccessContext.builder()
        .cluster(clusterName)
        .schemaActions(subjectName, SchemaAction.VIEW)
        .operationName("getAllVersionsBySubject")
        .build();

    Flux<SchemaSubjectDTO> schemas =
        schemaRegistryService.getAllVersionsBySubject(getCluster(clusterName), subjectName)
            .map(kafkaSrMapper::toDto);

    return validateAccess(context)
        .thenReturn(ResponseEntity.ok(schemas))
        .doOnEach(sig -> audit(context, sig));
  }

  @Override
  public Mono<ResponseEntity<CompatibilityLevelDTO>> getGlobalSchemaCompatibilityLevel(
      String clusterName, ServerWebExchange exchange) {
    return schemaRegistryService.getGlobalSchemaCompatibilityLevel(getCluster(clusterName))
        .map(c -> new CompatibilityLevelDTO().compatibility(kafkaSrMapper.toDto(c)))
        .map(ResponseEntity::ok)
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  @Override
  public Mono<ResponseEntity<SchemaSubjectDTO>> getLatestSchema(String clusterName,
                                                                String subject,
                                                                ServerWebExchange exchange) {
    var context = AccessContext.builder()
        .cluster(clusterName)
        .schemaActions(subject, SchemaAction.VIEW)
        .operationName("getLatestSchema")
        .build();

    return validateAccess(context).then(
        schemaRegistryService.getLatestSchemaVersionBySubject(getCluster(clusterName), subject)
            .map(kafkaSrMapper::toDto)
            .map(ResponseEntity::ok)
    ).doOnEach(sig -> audit(context, sig));
  }

  @Override
  public Mono<ResponseEntity<SchemaSubjectDTO>> getSchemaByVersion(
      String clusterName, String subject, Integer version, ServerWebExchange exchange) {
    var context = AccessContext.builder()
        .cluster(clusterName)
        .schemaActions(subject, SchemaAction.VIEW)
        .operationName("getSchemaByVersion")
        .operationParams(Map.of("subject", subject, "version", version))
        .build();

    return validateAccess(context).then(
        schemaRegistryService.getSchemaSubjectByVersion(
                getCluster(clusterName), subject, version)
            .map(kafkaSrMapper::toDto)
            .map(ResponseEntity::ok)
    ).doOnEach(sig -> audit(context, sig));
  }

  @Override
  public Mono<ResponseEntity<SchemaSubjectsResponseDTO>> getSchemas(String clusterName,
                                                                    @Valid Integer pageNum,
                                                                    @Valid Integer perPage,
                                                                    @Valid String search,
                                                                    ServerWebExchange serverWebExchange) {
    var context = AccessContext.builder()
        .cluster(clusterName)
        .operationName("getSchemas")
        .build();

    return schemaRegistryService
        .getAllSubjectNames(getCluster(clusterName))
        .flatMapIterable(l -> l)
        .filterWhen(schema -> accessControlService.isSchemaAccessible(schema, clusterName))
        .collectList()
        .flatMap(subjects -> {
          int pageSize = perPage != null && perPage > 0 ? perPage : DEFAULT_PAGE_SIZE;
          int subjectToSkip = ((pageNum != null && pageNum > 0 ? pageNum : 1) - 1) * pageSize;
          List<String> filteredSubjects = subjects
              .stream()
              .filter(subj -> search == null || StringUtils.containsIgnoreCase(subj, search))
              .sorted().toList();
          var totalPages = (filteredSubjects.size() / pageSize)
              + (filteredSubjects.size() % pageSize == 0 ? 0 : 1);
          List<String> subjectsToRender = filteredSubjects.stream()
              .skip(subjectToSkip)
              .limit(pageSize)
              .toList();
          return schemaRegistryService.getAllLatestVersionSchemas(getCluster(clusterName), subjectsToRender)
              .map(subjs -> subjs.stream().map(kafkaSrMapper::toDto).toList())
              .map(subjs -> new SchemaSubjectsResponseDTO().pageCount(totalPages).schemas(subjs));
        }).map(ResponseEntity::ok)
        .doOnEach(sig -> audit(context, sig));
  }

  @Override
  public Mono<ResponseEntity<Void>> updateGlobalSchemaCompatibilityLevel(
      String clusterName, @Valid Mono<CompatibilityLevelDTO> compatibilityLevelMono,
      ServerWebExchange exchange) {
    var context = AccessContext.builder()
        .cluster(clusterName)
        .schemaGlobalCompatChange()
        .operationName("updateGlobalSchemaCompatibilityLevel")
        .build();

    return validateAccess(context).then(
        compatibilityLevelMono
            .flatMap(compatibilityLevelDTO ->
                schemaRegistryService.updateGlobalSchemaCompatibility(
                    getCluster(clusterName),
                    kafkaSrMapper.fromDto(compatibilityLevelDTO.getCompatibility())
                ))
            .doOnEach(sig -> audit(context, sig))
            .thenReturn(ResponseEntity.ok().build())
    );
  }

  @Override
  public Mono<ResponseEntity<Void>> updateSchemaCompatibilityLevel(
      String clusterName, String subject, @Valid Mono<CompatibilityLevelDTO> compatibilityLevelMono,
      ServerWebExchange exchange) {

    var context = AccessContext.builder()
        .cluster(clusterName)
        .schemaActions(subject, SchemaAction.EDIT)
        .operationName("updateSchemaCompatibilityLevel")
        .operationParams(Map.of("subject", subject))
        .build();

    return compatibilityLevelMono.flatMap(compatibilityLevelDTO ->
        validateAccess(context).then(
                schemaRegistryService.updateSchemaCompatibility(
                    getCluster(clusterName),
                    subject,
                    kafkaSrMapper.fromDto(compatibilityLevelDTO.getCompatibility())
                ))
            .doOnEach(sig -> audit(context, sig))
            .thenReturn(ResponseEntity.ok().build())
    );
  }
}
