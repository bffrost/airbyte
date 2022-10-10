/*
 * Copyright (c) 2022 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.temporal.sync;

import io.airbyte.api.client.AirbyteApiClient;
import io.airbyte.api.client.invoker.generated.ApiException;
import io.airbyte.api.client.model.generated.JobIdRequestBody;
import io.airbyte.commons.functional.CheckedSupplier;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.temporal.CancellationHandler;
import io.airbyte.commons.temporal.TemporalUtils;
import io.airbyte.config.AirbyteConfigValidator;
import io.airbyte.config.ConfigSchema;
import io.airbyte.config.Configs.WorkerEnvironment;
import io.airbyte.config.OperatorDbtInput;
import io.airbyte.config.ResourceRequirements;
import io.airbyte.config.helpers.LogConfigs;
import io.airbyte.config.persistence.split_secrets.SecretsHydrator;
import io.airbyte.persistence.job.models.IntegrationLauncherConfig;
import io.airbyte.persistence.job.models.JobRunConfig;
import io.airbyte.commons.worker.ContainerOrchestratorConfig;
import io.airbyte.commons.worker.Worker;
import io.airbyte.commons.worker.WorkerConfigs;
import io.airbyte.commons.worker.general.DbtTransformationRunner;
import io.airbyte.commons.worker.general.DbtTransformationWorker;
import io.airbyte.commons.worker.normalization.NormalizationRunnerFactory;
import io.airbyte.commons.worker.process.ProcessFactory;
import io.airbyte.commons.worker.sync.DbtLauncherWorker;
import io.airbyte.workers.temporal.TemporalAttemptExecution;
import io.micronaut.context.annotation.Value;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Singleton
public class DbtTransformationActivityImpl implements DbtTransformationActivity {

  private final Optional<ContainerOrchestratorConfig> containerOrchestratorConfig;
  private final WorkerConfigs workerConfigs;
  private final ProcessFactory processFactory;
  private final SecretsHydrator secretsHydrator;
  private final Path workspaceRoot;
  private final WorkerEnvironment workerEnvironment;
  private final LogConfigs logConfigs;
  private final String airbyteVersion;
  private final Integer serverPort;
  private final AirbyteConfigValidator airbyteConfigValidator;
  private final TemporalUtils temporalUtils;
  private final AirbyteApiClient airbyteApiClient;

  public DbtTransformationActivityImpl(@Named("containerOrchestratorConfig") final Optional<ContainerOrchestratorConfig> containerOrchestratorConfig,
                                       @Named("defaultWorkerConfigs") final WorkerConfigs workerConfigs,
                                       @Named("defaultProcessFactory") final ProcessFactory processFactory,
                                       final SecretsHydrator secretsHydrator,
                                       @Named("workspaceRoot") final Path workspaceRoot,
                                       final WorkerEnvironment workerEnvironment,
                                       final LogConfigs logConfigs,
                                       @Value("${airbyte.version}") final String airbyteVersion,
                                       @Value("${micronaut.server.port}") final Integer serverPort,
                                       final AirbyteConfigValidator airbyteConfigValidator,
                                       final TemporalUtils temporalUtils,
                                       final AirbyteApiClient airbyteApiClient) {
    this.containerOrchestratorConfig = containerOrchestratorConfig;
    this.workerConfigs = workerConfigs;
    this.processFactory = processFactory;
    this.secretsHydrator = secretsHydrator;
    this.workspaceRoot = workspaceRoot;
    this.workerEnvironment = workerEnvironment;
    this.logConfigs = logConfigs;
    this.airbyteVersion = airbyteVersion;
    this.serverPort = serverPort;
    this.airbyteConfigValidator = airbyteConfigValidator;
    this.temporalUtils = temporalUtils;
    this.airbyteApiClient = airbyteApiClient;
  }

  @Override
  public Void run(final JobRunConfig jobRunConfig,
                  final IntegrationLauncherConfig destinationLauncherConfig,
                  final ResourceRequirements resourceRequirements,
                  final OperatorDbtInput input) {
    final ActivityExecutionContext context = Activity.getExecutionContext();
    return temporalUtils.withBackgroundHeartbeat(
        () -> {
          final var fullDestinationConfig = secretsHydrator.hydrate(input.getDestinationConfiguration());
          final var fullInput = Jsons.clone(input).withDestinationConfiguration(fullDestinationConfig);

          final Supplier<OperatorDbtInput> inputSupplier = () -> {
            airbyteConfigValidator.ensureAsRuntime(ConfigSchema.OPERATOR_DBT_INPUT, Jsons.jsonNode(fullInput));
            return fullInput;
          };

          final CheckedSupplier<Worker<OperatorDbtInput, Void>, Exception> workerFactory;

          if (containerOrchestratorConfig.isPresent()) {
            workerFactory =
                getContainerLauncherWorkerFactory(workerConfigs, destinationLauncherConfig, jobRunConfig,
                    () -> context);
          } else {
            workerFactory = getLegacyWorkerFactory(destinationLauncherConfig, jobRunConfig, resourceRequirements);
          }

          final TemporalAttemptExecution<OperatorDbtInput, Void> temporalAttemptExecution =
              new TemporalAttemptExecution<>(
                  workspaceRoot, workerEnvironment, logConfigs,
                  jobRunConfig,
                  workerFactory,
                  inputSupplier,
                  new CancellationHandler.TemporalCancellationHandler(context),
                  airbyteApiClient,
                  airbyteVersion,
                  () -> context);

          return temporalAttemptExecution.get();
        },
        () -> context);
  }

  private CheckedSupplier<Worker<OperatorDbtInput, Void>, Exception> getLegacyWorkerFactory(final IntegrationLauncherConfig destinationLauncherConfig,
                                                                                            final JobRunConfig jobRunConfig,
                                                                                            final ResourceRequirements resourceRequirements) {
    return () -> new DbtTransformationWorker(
        jobRunConfig.getJobId(),
        Math.toIntExact(jobRunConfig.getAttemptId()),
        resourceRequirements,
        new DbtTransformationRunner(
            processFactory, NormalizationRunnerFactory.create(
                destinationLauncherConfig.getDockerImage(),
                processFactory,
                NormalizationRunnerFactory.NORMALIZATION_VERSION)));
  }

  private CheckedSupplier<Worker<OperatorDbtInput, Void>, Exception> getContainerLauncherWorkerFactory(
                                                                                                       final WorkerConfigs workerConfigs,
                                                                                                       final IntegrationLauncherConfig destinationLauncherConfig,
                                                                                                       final JobRunConfig jobRunConfig,
                                                                                                       final Supplier<ActivityExecutionContext> activityContext)
      throws ApiException {
    final JobIdRequestBody id = new JobIdRequestBody();
    id.setId(Long.valueOf(jobRunConfig.getJobId()));
    final var jobScope = airbyteApiClient.getJobsApi().getJobInfo(id).getJob().getConfigId();
    final var connectionId = UUID.fromString(jobScope);

    return () -> new DbtLauncherWorker(
        connectionId,
        destinationLauncherConfig,
        jobRunConfig,
        workerConfigs,
        containerOrchestratorConfig.get(),
        activityContext,
        serverPort,
        temporalUtils);
  }

}
