/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
package gobblin.aws;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.FileUtils;
import org.apache.commons.mail.EmailException;
import org.apache.helix.Criteria;
import org.apache.helix.HelixManager;
import org.apache.helix.HelixManagerFactory;
import org.apache.helix.InstanceType;
import org.apache.helix.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.autoscaling.model.Tag;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;
import com.google.common.io.Closer;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.ServiceManager;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import gobblin.cluster.GobblinClusterConfigurationKeys;
import gobblin.cluster.GobblinClusterUtils;
import gobblin.cluster.HelixMessageSubTypes;
import gobblin.cluster.HelixUtils;
import gobblin.util.ConfigUtils;
import gobblin.util.EmailUtils;
import gobblin.util.ExecutorsUtils;

/**
 * A client driver to launch Gobblin as an AWS Cluster.
 *
 * <p>
 *   This class, upon starting, will check if there's an AWS Cluster that it has previously running and
 *   it is able to reconnect to. More specifically, it checks if an cluster with the same cluster name
 *   exists and can be reconnected to, i.e., if the cluster has not completed yet. If so, it simply starts
 *   monitoring that cluster.
 * </p>
 *
 * <p>
 *   On the other hand, if there's no such a reconnectable AWS cluster, This class will launch a new AWS
 *   cluster and start the {@link GobblinAWSClusterMaster}. It also persists the new cluster details so it
 *   is able to reconnect to the AWS cluster if it is restarted for some reason. Once the cluster is
 *   launched, this class starts to monitor the cluster by periodically polling the status of the cluster
 *   through a {@link ListeningExecutorService}.
 * </p>
 *
 * <p>
 *   If a shutdown signal is received, it sends a Helix
 *   {@link org.apache.helix.model.Message.MessageType#SCHEDULER_MSG} to the {@link GobblinAWSClusterMaster}
 *   asking it to shutdown and release all the allocated containers. It also sends an email notification for
 *   the shutdown if {@link GobblinAWSConfigurationKeys#EMAIL_NOTIFICATION_ON_SHUTDOWN_KEY} is {@code true}.
 * </p>
 *
 * @author Abhishek Tiwari
 */
public class GobblinAWSClusterLauncher {
  private static final Logger LOGGER = LoggerFactory.getLogger(GobblinAWSClusterLauncher.class);

  private static final Splitter SPLITTER = Splitter.on(',').omitEmptyStrings().trimResults();
  private static final String STDOUT = "stdout";
  private static final String STDERR = "stderr";

  private final Config config;

  private final HelixManager helixManager;

  private final EventBus eventBus = new EventBus(GobblinAWSClusterLauncher.class.getSimpleName());

  private AWSClusterSecurityManager awsClusterSecurityManager;
  private final ScheduledExecutorService clusterStatusMonitor;
  private final long clusterReportIntervalMinutes;

  private final Optional<String> masterJvmArgs;
  private final Optional<String> workerJvmArgs;

  private final String sinkLogRootDir;

  private final Closer closer = Closer.create();

  // AWS cluster meta
  private final String clusterName;
  private volatile Optional<String> clusterId = Optional.absent();

  private volatile Optional<ServiceManager> serviceManager = Optional.absent();

  // Maximum number of consecutive failures allowed to get the ClusterReport
  private final int maxGetClusterReportFailures;

  // A count on the number of consecutive failures on getting the ClusterReport
  private final AtomicInteger getClusterReportFailureCount = new AtomicInteger();

  // This flag tells if the AWS cluster has already completed. This is used to
  // tell if it is necessary to send a shutdown message to the ClusterMaster.
  private volatile boolean clusterCompleted = false;

  private volatile boolean stopped = false;

  private final boolean emailNotificationOnShutdown;

  // AWS Gobblin cluster common config
  private final String awsRegion;

  // AWS Gobblin Master Instance config
  private final String masterAmiId;
  private final String masterInstanceType;
  private final String masterJvmMemory;

  // AWS Gobblin Worker Instance config
  private final String workerAmiId;
  private final String workerInstanceType;
  private final String workerJvmMemory;
  private final Integer minWorkers;
  private final Integer maxWorkers;
  private final Integer desiredWorkers;

  // A generator for an integer ID of a Helix instance (participant)
  private final AtomicInteger helixInstanceIdGenerator = new AtomicInteger(0);

  public GobblinAWSClusterLauncher(Config config) throws IOException {
    this.config = config;

    this.clusterName = config.getString(GobblinAWSConfigurationKeys.CLUSTER_NAME_KEY);

    String zkConnectionString = config.getString(GobblinClusterConfigurationKeys.ZK_CONNECTION_STRING_KEY);
    LOGGER.info("Using ZooKeeper connection string: " + zkConnectionString);

    this.helixManager = HelixManagerFactory
        .getZKHelixManager(config.getString(GobblinClusterConfigurationKeys.HELIX_CLUSTER_NAME_KEY),
            GobblinClusterUtils.getHostname(), InstanceType.SPECTATOR, zkConnectionString);

    this.awsRegion = config.getString(GobblinAWSConfigurationKeys.AWS_REGION);
    this.masterAmiId = config.getString(GobblinAWSConfigurationKeys.MASTER_AMI_ID);
    this.masterInstanceType = config.getString(GobblinAWSConfigurationKeys.MASTER_INSTANCE_TYPE);
    this.masterJvmMemory = config.getString(GobblinAWSConfigurationKeys.MASTER_JVM_MEMORY);
    this.workerAmiId = config.getString(GobblinAWSConfigurationKeys.WORKER_AMI_ID);
    this.workerInstanceType = config.getString(GobblinAWSConfigurationKeys.WORKER_INSTANCE_TYPE);
    this.workerJvmMemory = config.getString(GobblinAWSConfigurationKeys.WORKER_JVM_MEMORY);
    this.minWorkers = config.getInt(GobblinAWSConfigurationKeys.MIN_WORKERS);
    this.maxWorkers = config.getInt(GobblinAWSConfigurationKeys.MAX_WORKERS);
    this.desiredWorkers = config.getInt(GobblinAWSConfigurationKeys.DESIRED_WORKERS);

    this.clusterStatusMonitor = Executors.newSingleThreadScheduledExecutor(
        ExecutorsUtils.newThreadFactory(Optional.of(LOGGER), Optional.of("GobblinAWSClusterStatusMonitor")));
    this.clusterReportIntervalMinutes = config.getLong(GobblinAWSConfigurationKeys.CLUSTER_REPORT_INTERVAL_MINUTES_KEY);

    this.masterJvmArgs = config.hasPath(GobblinAWSConfigurationKeys.MASTER_JVM_ARGS_KEY) ?
        Optional.of(config.getString(GobblinAWSConfigurationKeys.MASTER_JVM_ARGS_KEY)) :
        Optional.<String>absent();
    this.workerJvmArgs = config.hasPath(GobblinAWSConfigurationKeys.WORKER_JVM_ARGS_KEY) ?
        Optional.of(config.getString(GobblinAWSConfigurationKeys.WORKER_JVM_ARGS_KEY)) :
        Optional.<String>absent();

    this.sinkLogRootDir = config.getString(GobblinAWSConfigurationKeys.LOGS_SINK_ROOT_DIR_KEY);

    this.maxGetClusterReportFailures = config.getInt(GobblinAWSConfigurationKeys.MAX_GET_CLUSTER_REPORT_FAILURES_KEY);

    this.emailNotificationOnShutdown =
        config.getBoolean(GobblinAWSConfigurationKeys.EMAIL_NOTIFICATION_ON_SHUTDOWN_KEY);
  }

  /**
   * Launch a new Gobblin cluster on AWS.
   *
   * @throws IOException if there's something wrong launching the cluster
   */
  public void launch() throws IOException {
    this.eventBus.register(this);

    // Create Helix cluster and connect to it
    String helixClusterName = this.config.getString(GobblinClusterConfigurationKeys.HELIX_CLUSTER_NAME_KEY);
    HelixUtils
        .createGobblinHelixCluster(this.config.getString(GobblinClusterConfigurationKeys.ZK_CONNECTION_STRING_KEY),
            helixClusterName);
    LOGGER.info("Created Helix cluster " + helixClusterName);

    connectHelixManager();

    // Start all the services running
    // TODO: Add log copier service
    List<Service> services = Lists.newArrayList();
    this.awsClusterSecurityManager = new AWSClusterSecurityManager(this.config);
    services.add(this.awsClusterSecurityManager);
    this.serviceManager = Optional.of(new ServiceManager(services));
    this.serviceManager.get().startAsync();

    // Core logic to launch cluster
    this.clusterId = getClusterId();

    // TODO: Add cluster monitoring
  }

  /**
   * Stop this {@link GobblinAWSClusterLauncher} instance.
   *
   * @throws IOException if this {@link GobblinAWSClusterLauncher} instance fails to clean up its working directory.
   */
  public synchronized void stop() throws IOException, TimeoutException {
    if (this.stopped) {
      return;
    }

    LOGGER.info("Stopping the " + GobblinAWSClusterLauncher.class.getSimpleName());

    try {
      if (this.clusterId.isPresent()) {
        sendShutdownRequest();
      }

      if (this.serviceManager.isPresent()) {
        this.serviceManager.get().stopAsync().awaitStopped(5, TimeUnit.MINUTES);
      }

      ExecutorsUtils.shutdownExecutorService(this.clusterStatusMonitor, Optional.of(LOGGER), 5, TimeUnit.MINUTES);

      disconnectHelixManager();
    } finally {
      try {
        if (this.clusterId.isPresent()) {
           cleanUpClusterWorkDirectory(this.clusterId.get());
        }
      } finally {
        this.closer.close();
      }
    }

    this.stopped = true;
  }

  @VisibleForTesting
  void connectHelixManager() {
    try {
      this.helixManager.connect();
    } catch (Exception e) {
      LOGGER.error("HelixManager failed to connect", e);
      throw Throwables.propagate(e);
    }
  }

  @VisibleForTesting
  void disconnectHelixManager() {
    if (this.helixManager.isConnected()) {
      this.helixManager.disconnect();
    }
  }

  private Optional<String> getClusterId() throws IOException {
    Optional<String> reconnectableClusterId = getReconnectableClusterId();
    if (reconnectableClusterId.isPresent()) {
      LOGGER.info("Found reconnectable cluster with cluster ID: " + reconnectableClusterId.get());
      return reconnectableClusterId;
    }

    LOGGER.info("No reconnectable cluster found so creating a cluster");
    return Optional.of(setupGobblinCluster());
  }

  @VisibleForTesting
  Optional<String> getReconnectableClusterId() throws IOException {
    // TODO: Discover all available ASG's and reconnect if there is an ClusterMaster

    return Optional.absent();
  }

  /**
   * Setup the Gobblin AWS cluster.
   *
   * @throws IOException if there's anything wrong setting up the AWS cluster
   */
  @VisibleForTesting
  String setupGobblinCluster() throws IOException {

    String uuid = UUID.randomUUID().toString();

    // Create security group
    // TODO: Make security group restrictive and permission set configurable
    String securityGroupName = "GobblinSecurityGroup_" + uuid;
    AWSSdkClient.createSecurityGroup(this.awsClusterSecurityManager,
        Regions.fromName(this.awsRegion),
        securityGroupName,
        "Gobblin cluster security group");
    AWSSdkClient.addPermissionsToSecurityGroup(this.awsClusterSecurityManager,
        Regions.fromName(this.awsRegion),
        securityGroupName,
        "0.0.0.0/0",
        "tcp",
        0,
        65535);

    // Create key value pair
    String keyName = "GobblinKey_" + uuid;
    String material = AWSSdkClient.createKeyValuePair(this.awsClusterSecurityManager,
        Regions.fromName(this.awsRegion),
        keyName);
    // TODO: save material for later

    // Launch Cluster Master
    String clusterId = launchClusterMaster(uuid, keyName, securityGroupName);

    // Launch WorkUnit runners
    launchWorkUnitRunners(uuid, keyName, securityGroupName);

    return clusterId;
  }

  private String launchClusterMaster(String uuid, String keyName, String securityGroups) {
    String userData = buildClusterMasterCommand(this.masterJvmMemory);

    // Create launch config for Cluster master
    String launchConfigName = "GobblinMasterLaunchConfig_" + uuid;
    AWSSdkClient.createLaunchConfig(this.awsClusterSecurityManager,
        Regions.fromName(this.awsRegion),
        launchConfigName,
        this.masterAmiId,
        this.masterInstanceType,
        keyName,
        securityGroups,
        null,
        null,
        null,
        null,
        null,
        userData);

    // Create ASG for Cluster master
    // TODO: Make size configurable when we have a solid multi-master story
    int minNumMasters = 1;
    int maxNumMasters = 1;
    int desiredNumMasters = 1;
    Tag tag = new Tag().withKey("GobblinMaster").withValue(uuid);
    AWSSdkClient.createAutoScalingGroup(this.awsClusterSecurityManager,
        Regions.fromName(this.awsRegion),
        securityGroups,
        launchConfigName,
        minNumMasters,
        maxNumMasters,
        desiredNumMasters,
        null,
        null,
        null,
        null,
        null,
        tag,
        null);

    return "GobblinClusterMaster_" + uuid;
  }

  private void launchWorkUnitRunners(String uuid, String keyName,
      String securityGroups) {
    String userData = buildClusterWorkerCommand(this.workerJvmMemory);

    // Create launch config for Cluster master
    String launchConfigName = "GobblinWorkerLaunchConfig_" + uuid;
    AWSSdkClient.createLaunchConfig(this.awsClusterSecurityManager,
        Regions.fromName(this.awsRegion),
        launchConfigName,
        this.workerAmiId,
        this.workerInstanceType,
        keyName,
        securityGroups,
        null,
        null,
        null,
        null,
        null,
        userData);

    // Create ASG for Cluster workers
    Tag tag = new Tag().withKey("GobblinWorker").withValue(uuid);
    AWSSdkClient.createAutoScalingGroup(this.awsClusterSecurityManager,
        Regions.fromName(this.awsRegion),
        securityGroups,
        launchConfigName,
        this.minWorkers,
        this.maxWorkers,
        this.desiredWorkers,
        null,
        null,
        null,
        null,
        null,
        tag,
        null);
  }

  private String buildClusterMasterCommand(String memory) {
    StringBuilder userDataCmds = new StringBuilder();

    String clusterMasterClassName = GobblinAWSClusterMaster.class.getSimpleName();

    // Create NFS server
    // TODO: Replace with EFS when available in GA
    // Note: Until EFS availability, ClusterMaster is SPOF because we loose NFS when it's relaunched / replaced
    //       .. this can be worked around, but would be an un-necessary work
    String nfsRoot = "/var/gobblin";

    // Create various directories
    StringBuilder logDir = new StringBuilder().append(this.sinkLogRootDir).append(File.separator).append("logs");
    userDataCmds.append("mkdir -p ").append(logDir).append("\n");

    // Launch Gobblin Cluster Master
    StringBuilder launchGobblinClusterMasterCmd = new StringBuilder()
        .append("java")
        .append(" -Xmx").append(memory)
        .append(" ").append(this.masterJvmArgs.or(""))
        .append(" ").append(GobblinAWSClusterMaster.class.getName())
        .append(" --").append(GobblinClusterConfigurationKeys.APPLICATION_NAME_OPTION_NAME)
        .append(" ").append(this.clusterName)
        .append(" 1>").append(logDir).append(File.separator).append(clusterMasterClassName).append(".").append(
            GobblinAWSClusterLauncher.STDOUT)
        .append(" 2>").append(logDir).append(File.separator).append(clusterMasterClassName).append(".").append(
            GobblinAWSClusterLauncher.STDERR);
    userDataCmds.append(launchGobblinClusterMasterCmd);

    return userDataCmds.toString();
  }

  private String buildClusterWorkerCommand(String memory) {
    StringBuilder userDataCmds = new StringBuilder();

    String clusterWorkerClassName = GobblinAWSClusterMaster.class.getSimpleName();

    // Connect to NFS server
    // TODO: Replace with EFS when available in GA
    String nfsRoot = "/var/gobblin";

    // Create various directories
    StringBuilder logDir = new StringBuilder().append(this.sinkLogRootDir).append(File.separator).append("logs");
    userDataCmds.append("mkdir -p ").append(logDir).append("\n");

    // Launch Gobblin Worker
    StringBuilder launchGobblinClusterWorkerCmd = new StringBuilder().append("java").append(" -Xmx").append(memory)
        .append(" ").append(this.workerJvmArgs.or(""))
        .append(" ").append(GobblinAWSTaskRunner.class.getName())
        .append(" --").append(GobblinClusterConfigurationKeys.APPLICATION_NAME_OPTION_NAME)
        .append(" ").append(this.clusterName)
        .append(" --").append(GobblinClusterConfigurationKeys.HELIX_INSTANCE_NAME_OPTION_NAME)
        .append(" ").append(HelixUtils.getHelixInstanceName(GobblinAWSTaskRunner.class.getSimpleName(),
            helixInstanceIdGenerator.incrementAndGet()))
        .append(" 1>").append(logDir).append(File.separator).append(
        clusterWorkerClassName).append(".").append(GobblinAWSClusterLauncher.STDOUT)
        .append(" 2>").append(logDir).append(File.separator).append(
        clusterWorkerClassName).append(".").append(GobblinAWSClusterLauncher.STDERR);
    userDataCmds.append(launchGobblinClusterWorkerCmd);

    return userDataCmds.toString();
  }

  @VisibleForTesting
  void sendShutdownRequest() {
    Criteria criteria = new Criteria();
    criteria.setInstanceName("%");
    criteria.setResource("%");
    criteria.setPartition("%");
    criteria.setPartitionState("%");
    criteria.setRecipientInstanceType(InstanceType.CONTROLLER);
    criteria.setSessionSpecific(true);

    Message shutdownRequest = new Message(Message.MessageType.SHUTDOWN,
        HelixMessageSubTypes.APPLICATION_MASTER_SHUTDOWN.toString().toLowerCase() + UUID.randomUUID().toString());
    shutdownRequest.setMsgSubType(HelixMessageSubTypes.APPLICATION_MASTER_SHUTDOWN.toString());
    shutdownRequest.setMsgState(Message.MessageState.NEW);
    shutdownRequest.setTgtSessionId("*");

    int messagesSent = this.helixManager.getMessagingService().send(criteria, shutdownRequest);
    if (messagesSent == 0) {
      LOGGER.error(String.format("Failed to send the %s message to the controller", shutdownRequest.getMsgSubType()));
    }
  }

  private void cleanUpClusterWorkDirectory(String clusterId) throws IOException {
    File appWorkDir = new File(GobblinClusterUtils.getAppWorkDirPath(this.clusterName, clusterId));

    if (appWorkDir.exists() && appWorkDir.isDirectory()) {
      LOGGER.info("Deleting application working directory " + appWorkDir);
      FileUtils.deleteDirectory(appWorkDir);
    }
  }

  private void sendEmailOnShutdown(Optional<String> report) {
    String subject = String.format("Gobblin AWS cluster %s completed", this.clusterName);

    StringBuilder messageBuilder = new StringBuilder("Gobblin AWS cluster was shutdown at: " + new Date());
    if (report.isPresent()) {
      messageBuilder.append(' ').append(report.get());
    }

    try {
      EmailUtils.sendEmail(ConfigUtils.configToState(this.config), subject, messageBuilder.toString());
    } catch (EmailException ee) {
      LOGGER.error("Failed to send email notification on shutdown", ee);
    }
  }

  public static void main(String[] args) throws Exception {
    final GobblinAWSClusterLauncher gobblinAWSClusterLauncher =
        new GobblinAWSClusterLauncher(ConfigFactory.load());
    Runtime.getRuntime().addShutdownHook(new Thread() {

      @Override
      public void run() {
        try {
          gobblinAWSClusterLauncher.stop();
        } catch (IOException ioe) {
          LOGGER.error("Failed to shutdown the " + GobblinAWSClusterLauncher.class.getSimpleName(), ioe);
        } catch (TimeoutException te) {
          LOGGER.error("Timeout in stopping the service manager", te);
        } finally {
          if (gobblinAWSClusterLauncher.emailNotificationOnShutdown) {
            gobblinAWSClusterLauncher.sendEmailOnShutdown(Optional.<String>absent());
          }
        }
      }
    });

    gobblinAWSClusterLauncher.launch();
  }
}
