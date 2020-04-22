/*
 * Copyright (c) 2012-2018 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.workspace.infrastructure.kubernetes.provision;

import static java.util.Collections.singletonList;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.IntOrStringBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.ServiceSpec;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.eclipse.che.api.core.ConflictException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.model.workspace.runtime.RuntimeIdentity;
import org.eclipse.che.api.ssh.server.SshManager;
import org.eclipse.che.api.ssh.server.model.impl.SshPairImpl;
import org.eclipse.che.api.ssh.shared.model.SshPair;
import org.eclipse.che.api.workspace.server.spi.InfrastructureException;
import org.eclipse.che.workspace.infrastructure.kubernetes.KubernetesClientFactory;
import org.eclipse.che.workspace.infrastructure.kubernetes.Names;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AsyncStorageProvisioner {

  private static final String SSH_SERVICE_NAME = "async-storage";
  private static final String SSH_KEY_NAME = "rsync-via-ssh";
  private static final String SSH_CONFIG = "ssh_config";
  private static final String CONFIG_MAP_VOLUME_NAME = "async-storage-configvolume";
  private static final String ASYNC_STORAGE_CONFIG = "async-storage-config";
  private static final String ETC_PATH = "/etc/";
  private static final String RSYNC = "rsync.pub";
  private static final String SSH_KEY_PATH = ETC_PATH + RSYNC;
  private static final String STORAGE_DATA_PATH = "/var/lib/storage/data/";
  private static final String STORAGE_DATA = "storage-data";

  private static final Logger LOG = LoggerFactory.getLogger(AsyncStorageProvisioner.class);

  private final SshManager sshManager;
  private final KubernetesClientFactory clientFactory;

  @Inject
  public AsyncStorageProvisioner(
      SshManager sshManager, KubernetesClientFactory kubernetesClientFactory) {
    this.sshManager = sshManager;
    this.clientFactory = kubernetesClientFactory;
  }

  public void provision(RuntimeIdentity identity) throws InfrastructureException {
    List<SshPairImpl> sshPairs;
    try {
      sshPairs = sshManager.getPairs(identity.getOwnerId(), SSH_SERVICE_NAME);
    } catch (ServerException e) {
      LOG.warn("Unable to get SSH Keys. Cause: {}", e.getMessage());
      return;
    }
    if (sshPairs.isEmpty()) {
      try {
        sshPairs =
            singletonList(
                sshManager.generatePair(identity.getOwnerId(), SSH_SERVICE_NAME, SSH_KEY_NAME));
      } catch (ServerException | ConflictException e) {
        LOG.warn("Unable to generate the initial SSH key. Cause: {}", e.getMessage());
        return;
      }
    }
    String namespace = identity.getInfrastructureNamespace();
    create(namespace, sshPairs.get(0));
  }

  private void create(String namespace, SshPair sshPair) throws InfrastructureException {
    KubernetesClient oc = clientFactory.create();

    String configMapName = namespace + ASYNC_STORAGE_CONFIG;

    Map<String, String> sshConfigData = new HashMap<>();
    sshConfigData.put(SSH_CONFIG, sshPair.getPublicKey());
    ConfigMap configMap =
        new ConfigMapBuilder()
            .withNewMetadata()
            .withName(configMapName)
            .endMetadata()
            .withData(sshConfigData)
            .build();

    oc.configMaps().create(configMap);

    Pod pod = createStoragePod(namespace, configMap);
    oc.pods().create(pod);

    Service service = createStorageService(namespace);
    oc.services().create(service);
  }

  private Pod createStoragePod(String namespace, ConfigMap configMap) {
    String containerName = Names.generateName("storage");

    PodSpecBuilder podSpecBuilder = new PodSpecBuilder();
    PodSpec podSpec =
        podSpecBuilder
            .withContainers(
                new ContainerBuilder()
                    .withName(containerName)
                    .withImage("vparfonov/che-data-sync-pod:latest")
                    .withNewResources()
                    .addToLimits("memory", new Quantity("512Mi"))
                    .addToRequests("memory", new Quantity("256Mi"))
                    .endResources()
                    .withPorts(
                        new ContainerPortBuilder()
                            .withContainerPort(2222)
                            .withProtocol("TCP")
                            .build())
                    .withVolumeMounts(
                        new VolumeMount(STORAGE_DATA_PATH,
                            null,
                            STORAGE_DATA,
                            false,
                            null,
                            null),
                        new VolumeMount(
                            SSH_KEY_PATH,
                            null,
                            CONFIG_MAP_VOLUME_NAME,
                            true,
                            RSYNC,
                            null))
                    .build())
            .withVolumes(
                new VolumeBuilder()
                    .withName(STORAGE_DATA)
                    .withPersistentVolumeClaim(
                        new PersistentVolumeClaimVolumeSourceBuilder()
                            .withClaimName(STORAGE_DATA)
                            .withReadOnly(false)
                            .build())
                    .build(),
                new VolumeBuilder()
                    .withName(CONFIG_MAP_VOLUME_NAME)
                    .withConfigMap(
                        new ConfigMapVolumeSourceBuilder()
                            .withName(configMap.getMetadata().getName())
                            .build())
                    .build())
            .build();

    return new PodBuilder()
        .withApiVersion("v1")
        .withKind("Pod")
        .withNewMetadata()
        .withName("storage")
        .withNamespace(namespace)
        .withLabels(Collections.singletonMap("app", "storage"))
        .endMetadata()
        .withSpec(podSpec)
        .build();
  }

  private Service createStorageService(String namespace) {
    ObjectMeta meta = new ObjectMeta();
    meta.setName("storage");
    meta.setNamespace(namespace);

    IntOrString targetPort =
        new IntOrStringBuilder().withIntVal(2222).withStrVal(String.valueOf(2222)).build();

    ServicePort port =
        new ServicePortBuilder()
            .withName("2222")
            .withProtocol("TCP")
            .withPort(2222)
            .withTargetPort(targetPort)
            .build();
    ServiceSpec spec = new ServiceSpec();
    spec.setPorts(Arrays.asList(port));
    spec.setSelector(Collections.singletonMap("app", "storage"));

    Service service = new Service();
    service.setApiVersion("v1");
    service.setKind("Service");
    service.setMetadata(meta);
    service.setSpec(spec);

    return service;
  }
}
