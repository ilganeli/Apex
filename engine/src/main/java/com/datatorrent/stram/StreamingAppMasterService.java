/*
 *  Copyright (c) 2012-2013 DataTorrent, Inc.
 *  All Rights Reserved.
 */
package com.datatorrent.stram;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.xml.bind.annotation.XmlElement;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.service.CompositeService;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.protocolrecords.FinishApplicationMasterRequest;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.api.AMRMClient;
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.client.api.async.NMClientAsync;
import org.apache.hadoop.yarn.client.api.async.impl.NMClientAsyncImpl;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.util.Clock;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.util.Records;
import org.apache.hadoop.yarn.util.SystemClock;
import org.apache.hadoop.yarn.webapp.WebApp;
import org.apache.hadoop.yarn.webapp.WebApps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

import com.datatorrent.api.Attribute;
import com.datatorrent.api.Context.DAGContext;
import com.datatorrent.api.DAG;
import com.datatorrent.api.StringCodec;

import com.datatorrent.stram.StreamingContainerManager.ContainerResource;
import com.datatorrent.stram.api.BaseContext;
import com.datatorrent.stram.api.StramEvent;
import com.datatorrent.stram.engine.StreamingContainer;
import com.datatorrent.stram.license.License;
import com.datatorrent.stram.license.LicenseAuthority;
import com.datatorrent.stram.license.LicensingAgentClient;
import com.datatorrent.stram.plan.logical.LogicalPlan;
import com.datatorrent.stram.plan.physical.OperatorStatus.PortStatus;
import com.datatorrent.stram.plan.physical.PTContainer;
import com.datatorrent.stram.plan.physical.PTOperator;
import com.datatorrent.stram.security.StramDelegationTokenIdentifier;
import com.datatorrent.stram.security.StramDelegationTokenManager;
import com.datatorrent.stram.security.StramWSFilterInitializer;
import com.datatorrent.stram.webapp.AppInfo;
import com.datatorrent.stram.webapp.StramWebApp;

import static java.lang.Thread.sleep;

/**
 * Streaming Application Master
 *
 * @since 0.3.2
 */
public class StreamingAppMasterService extends CompositeService
{
  private static final Logger LOG = LoggerFactory.getLogger(StreamingAppMasterService.class);
  private static final long DELEGATION_KEY_UPDATE_INTERVAL = 24 * 60 * 60 * 1000;
  private static final long DELEGATION_TOKEN_MAX_LIFETIME = Long.MAX_VALUE / 2;
  private static final long DELEGATION_TOKEN_RENEW_INTERVAL = Long.MAX_VALUE / 2;
  private static final long DELEGATION_TOKEN_REMOVER_SCAN_INTERVAL = 24 * 60 * 60 * 1000;
  private static final int NUMBER_MISSED_HEARTBEATS = 30;
  private AMRMClient<ContainerRequest> amRmClient;
  private NMClientAsync nmClient;
  private LogicalPlan dag;
  // Application Attempt Id ( combination of attemptId and fail count )
  final private ApplicationAttemptId appAttemptID;
  // Hostname of the container
  private final String appMasterHostname = "";
  // Tracking url to which app master publishes info for clients to monitor
  private String appMasterTrackingUrl = "";
  // Simple flag to denote whether all works is done
  private boolean appDone = false;
  // Counter for completed containers ( complete denotes successful or failed )
  private final AtomicInteger numCompletedContainers = new AtomicInteger();
  // Containers that the RM has allocated to us
  private final ConcurrentMap<String, AllocatedContainer> allocatedContainers = Maps.newConcurrentMap();
  // Count of failed containers
  private final AtomicInteger numFailedContainers = new AtomicInteger();
  private final ConcurrentLinkedQueue<Runnable> pendingTasks = new ConcurrentLinkedQueue<Runnable>();
  // child container callback
  private StreamingContainerParent heartbeatListener;
  private StreamingContainerManager dnmgr;
  private final Clock clock = new SystemClock();
  private final long startTime = clock.getTime();
  private final ClusterAppStats stats = new ClusterAppStats();
  private StramDelegationTokenManager delegationTokenManager = null;
  private LicensingAgentClient licenseClient;
  private License.LicenseType licenseType;

  public StreamingAppMasterService(ApplicationAttemptId appAttemptID)
  {
    super(StreamingAppMasterService.class.getName());
    this.appAttemptID = appAttemptID;
  }

  /**
   * Overrides getters to pull live info.
   */
  protected class ClusterAppStats extends AppInfo.AppStats
  {
    @Override
    public int getAllocatedContainers()
    {
      return allocatedContainers.size();
    }

    @Override
    public int getPlannedContainers()
    {
      return dnmgr.getPhysicalPlan().getContainers().size();
    }

    @Override
    @XmlElement
    public int getFailedContainers()
    {
      return numFailedContainers.get();
    }

    @Override
    public int getNumOperators()
    {
      return dnmgr.getPhysicalPlan().getAllOperators().size();
    }

    @Override
    public long getCurrentWindowId()
    {
      long min = Long.MAX_VALUE;
      for (Map.Entry<Integer, PTOperator> entry : dnmgr.getPhysicalPlan().getAllOperators().entrySet()) {
        long windowId = entry.getValue().stats.currentWindowId.get();
        if (min > windowId) {
          min = windowId;
        }
      }
      return StreamingContainerManager.toWsWindowId(min == Long.MAX_VALUE ? 0 : min);
    }

    @Override
    public long getRecoveryWindowId()
    {
      return StreamingContainerManager.toWsWindowId(dnmgr.getCommittedWindowId());
    }

    @Override
    public long getTuplesProcessedPSMA()
    {
      long result = 0;
      for (Map.Entry<Integer, PTOperator> entry : dnmgr.getPhysicalPlan().getAllOperators().entrySet()) {
        result += entry.getValue().stats.tuplesProcessedPSMA.get();
      }
      return result;
    }

    @Override
    public long getTotalTuplesProcessed()
    {
      long result = 0;
      for (Map.Entry<Integer, PTOperator> entry : dnmgr.getPhysicalPlan().getAllOperators().entrySet()) {
        result += entry.getValue().stats.totalTuplesProcessed.get();
      }
      return result;
    }

    @Override
    public long getTuplesEmittedPSMA()
    {
      long result = 0;
      for (Map.Entry<Integer, PTOperator> entry : dnmgr.getPhysicalPlan().getAllOperators().entrySet()) {
        result += entry.getValue().stats.tuplesEmittedPSMA.get();
      }
      return result;
    }

    @Override
    public long getTotalTuplesEmitted()
    {
      long result = 0;
      for (Map.Entry<Integer, PTOperator> entry : dnmgr.getPhysicalPlan().getAllOperators().entrySet()) {
        result += entry.getValue().stats.totalTuplesEmitted.get();
      }
      return result;
    }

    @Override
    public long getTotalMemoryAllocated()
    {
      long result = 0;
      for (PTContainer c : dnmgr.getPhysicalPlan().getContainers()) {
        result += c.getAllocatedMemoryMB();
      }
      return result;
    }

    @Override
    public long getTotalBufferServerReadBytesPSMA()
    {
      long result = 0;
      for (Map.Entry<Integer, PTOperator> entry : dnmgr.getPhysicalPlan().getAllOperators().entrySet()) {
        for (Map.Entry<String, PortStatus> portEntry : entry.getValue().stats.inputPortStatusList.entrySet()) {
          result += portEntry.getValue().bufferServerBytesPMSMA.getAvg() * 1000;
        }
      }
      return result;
    }

    @Override
    public long getTotalBufferServerWriteBytesPSMA()
    {
      long result = 0;
      for (Map.Entry<Integer, PTOperator> entry : dnmgr.getPhysicalPlan().getAllOperators().entrySet()) {
        for (Map.Entry<String, PortStatus> portEntry : entry.getValue().stats.outputPortStatusList.entrySet()) {
          result += portEntry.getValue().bufferServerBytesPMSMA.getAvg() * 1000;
        }
      }
      return result;
    }

    @Override
    public List<Integer> getCriticalPath()
    {
      StreamingContainerManager.CriticalPathInfo criticalPathInfo = dnmgr.getCriticalPathInfo();
      return (criticalPathInfo == null) ? null : criticalPathInfo.path;
    }

    @Override
    public long getLatency()
    {
      StreamingContainerManager.CriticalPathInfo criticalPathInfo = dnmgr.getCriticalPathInfo();
      return (criticalPathInfo == null) ? 0 : criticalPathInfo.latency;
    }

  }

  private class ClusterAppContextImpl extends BaseContext implements StramAppContext
  {
    private ClusterAppContextImpl()
    {
      super(null, null);
    }

    ClusterAppContextImpl(Attribute.AttributeMap attributes)
    {
      super(attributes, null);
    }

    @Override
    public ApplicationId getApplicationID()
    {
      return appAttemptID.getApplicationId();
    }

    @Override
    public ApplicationAttemptId getApplicationAttemptId()
    {
      return appAttemptID;
    }

    @Override
    public String getApplicationName()
    {
      return getValue(LogicalPlan.APPLICATION_NAME);
    }

    @Override
    public String getApplicationDocLink()
    {
      return getValue(LogicalPlan.APPLICATION_DOC_LINK);
    }

    @Override
    public long getStartTime()
    {
      return startTime;
    }

    @Override
    public String getApplicationPath()
    {
      return getValue(LogicalPlan.APPLICATION_PATH);
    }

    @Override
    public CharSequence getUser()
    {
      return System.getenv(ApplicationConstants.Environment.USER.toString());
    }

    @Override
    public Clock getClock()
    {
      return clock;
    }

    @Override
    public String getAppMasterTrackingUrl()
    {
      return appMasterTrackingUrl;
    }

    @Override
    public ClusterAppStats getStats()
    {
      return stats;
    }

    @Override
    public String getGatewayAddress()
    {
      return getValue(LogicalPlan.GATEWAY_CONNECT_ADDRESS);
    }

    @Override
    public String getLicenseId()
    {
      if (StreamingAppMasterService.this.licenseClient != null) {
        return StreamingAppMasterService.this.licenseClient.getLicenseId();
      }
      return "";
    }

    @Override
    public long getRemainingLicensedMB()
    {
      if (StreamingAppMasterService.this.licenseClient != null) {
        return StreamingAppMasterService.this.licenseClient.getRemainingLicensedMB();
      }
      return 0;
    }

    @Override
    public long getTotalLicensedMB()
    {
      if (StreamingAppMasterService.this.licenseClient != null) {
        return StreamingAppMasterService.this.licenseClient.getTotalLicensedMB();
      }
      return 0;
    }

    @Override
    public long getAllocatedMB()
    {
      if (StreamingAppMasterService.this.licenseClient != null) {
        return StreamingAppMasterService.this.licenseClient.getAllocatedMB();
      }
      return 0;
    }

    @Override
    public long getLicenseInfoLastUpdate()
    {
      if (StreamingAppMasterService.this.licenseClient != null) {
        return StreamingAppMasterService.this.licenseClient.getLicenseInfoLastUpdate();
      }
      return 0;
    }

    @Override
    public boolean isGatewayConnected()
    {
      if (StreamingAppMasterService.this.dnmgr != null) {
        return StreamingAppMasterService.this.dnmgr.isGatewayConnected();
      }
      return false;
    }

    @SuppressWarnings("FieldNameHidesFieldInSuperclass")
    private static final long serialVersionUID = 201309112304L;
  }

  /**
   * Dump out contents of $CWD and the environment to stdout for debugging
   */
  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public void dumpOutDebugInfo()
  {
    LOG.info("Dump debug output");
    Map<String, String> envs = System.getenv();
    LOG.info("\nDumping System Env: begin");
    for (Map.Entry<String, String> env : envs.entrySet()) {
      LOG.info("System env: key=" + env.getKey() + ", val=" + env.getValue());
    }
    LOG.info("Dumping System Env: end");

    String cmd = "ls -al";
    Runtime run = Runtime.getRuntime();
    Process pr;
    try {
      pr = run.exec(cmd);
      pr.waitFor();

      BufferedReader buf = new BufferedReader(new InputStreamReader(pr.getInputStream()));
      String line;
      LOG.info("\nDumping files in local dir: begin");
      try {
        while ((line = buf.readLine()) != null) {
          LOG.info("System CWD content: " + line);
        }
        LOG.info("Dumping files in local dir: end");
      }
      finally {
        buf.close();
      }
    }
    catch (IOException e) {
      LOG.debug("Exception", e);
    }
    catch (InterruptedException e) {
      LOG.info("Interrupted", e);
    }

    LOG.info("Classpath: {}", System.getProperty("java.class.path"));
    LOG.info("Config resources: {}", getConfig().toString());
    try {
      // find a better way of logging this using the logger.
      Configuration.dumpConfiguration(getConfig(), new PrintWriter(System.out));
    }
    catch (Exception e) {
      LOG.error("Error dumping configuration.", e);
    }

  }

  @Override
  protected void serviceInit(Configuration conf) throws Exception
  {
    LOG.info("Application master" + ", appId=" + appAttemptID.getApplicationId().getId() + ", clustertimestamp=" + appAttemptID.getApplicationId().getClusterTimestamp() + ", attemptId=" + appAttemptID.getAttemptId());

    FileInputStream fis = new FileInputStream("./" + LogicalPlan.SER_FILE_NAME);
    try {
      this.dag = LogicalPlan.read(fis);
    }
    finally {
      fis.close();
    }
    // "debug" simply dumps all data using LOG.info
    if (dag.isDebug()) {
      dumpOutDebugInfo();
    }

    FSRecoveryHandler recoveryHandler = new FSRecoveryHandler(dag.assertAppPath(), conf);
    this.dnmgr = StreamingContainerManager.getInstance(recoveryHandler, dag, true);
    dag = this.dnmgr.getLogicalPlan();

    Map<Class<?>, Class<? extends StringCodec<?>>> codecs = dag.getAttributes().get(DAG.STRING_CODECS);
    StringCodecs.loadConverters(codecs);

    LOG.info("Starting application with {} operators in {} containers", dnmgr.getPhysicalPlan().getAllOperators().size(), dnmgr.getPhysicalPlan().getContainers().size());

    if (UserGroupInformation.isSecurityEnabled()) {
      // TODO :- Need to perform token renewal
      delegationTokenManager = new StramDelegationTokenManager(DELEGATION_KEY_UPDATE_INTERVAL, DELEGATION_TOKEN_MAX_LIFETIME, DELEGATION_TOKEN_RENEW_INTERVAL, DELEGATION_TOKEN_REMOVER_SCAN_INTERVAL);
    }
    this.nmClient = new NMClientAsyncImpl(new NMCallbackHandler());
    addService(nmClient);
    this.amRmClient = AMRMClient.createAMRMClient();
    addService(amRmClient);

    // start RPC server
    int rpcListenerCount = dag.getValue(DAGContext.HEARTBEAT_LISTENER_THREAD_COUNT);
    this.heartbeatListener = new StreamingContainerParent(this.getClass().getName(), dnmgr, delegationTokenManager, rpcListenerCount);
    addService(heartbeatListener);

    // get license and prepare for license agent interaction
    String licenseBase64 = dag.getValue(LogicalPlan.LICENSE);
    if (licenseBase64 != null) {
      byte[] licenseBytes = Base64.decodeBase64(licenseBase64);
      License license = LicenseAuthority.getLicense(licenseBytes);
      this.licenseType = license.getLicenseType();
      this.licenseClient = new LicensingAgentClient(appAttemptID.getApplicationId(), license);
      addService(this.licenseClient);
    }

    // initialize all services added above
    super.serviceInit(conf);
  }

  @Override
  protected void serviceStart() throws Exception
  {
    super.serviceStart();
    if (delegationTokenManager != null) {
      delegationTokenManager.startThreads();
    }

    // write the connect address for containers to DFS
    InetSocketAddress connectAddress = NetUtils.getConnectAddress(this.heartbeatListener.getAddress());
    URI connectUri = new URI("stram", null, connectAddress.getHostName(), connectAddress.getPort(), null, null, null);
    FSRecoveryHandler recoveryHandler = new FSRecoveryHandler(dag.assertAppPath(), getConfig());
    recoveryHandler.writeConnectUri(connectUri.toString());

    // start web service
    StramAppContext appContext = new ClusterAppContextImpl(dag.getAttributes());
    try {
      org.mortbay.log.Log.setLog(null);
    }
    catch (Throwable throwable) {
      // SPOI-2687. As part of Pivotal Certification, we need to catch ClassNotFoundException as Pivotal was using Jetty 7 where as other distros are using Jetty 6.
      // LOG.error("can't set the log to null: ", throwable);
    }

    try {
      Configuration config = getConfig();
      if (UserGroupInformation.isSecurityEnabled()) {
        config = new Configuration(config);
        config.set("hadoop.http.filter.initializers", StramWSFilterInitializer.class.getCanonicalName());
      }
      WebApp webApp = WebApps.$for("stram", StramAppContext.class, appContext, "ws").with(config).start(new StramWebApp(this.dnmgr));
      LOG.info("Started web service at port: " + webApp.port());
      this.appMasterTrackingUrl = NetUtils.getConnectAddress(webApp.getListenerAddress()).getHostName() + ":" + webApp.port();
      LOG.info("Setting tracking URL to: " + appMasterTrackingUrl);
    }
    catch (Exception e) {
      LOG.error("Webapps failed to start. Ignoring for now:", e);
    }
  }

  @Override
  protected void serviceStop() throws Exception
  {
    super.serviceStop();
    if (delegationTokenManager != null) {
      delegationTokenManager.stopThreads();
    }
    if (nmClient != null) {
      nmClient.stop();
    }
    if (amRmClient != null) {
      amRmClient.stop();
    }
    if (dnmgr != null) {
      dnmgr.teardown();
    }
  }

  public boolean run() throws Exception
  {
    boolean status = true;
    try {
      StreamingContainer.eventloop.start();
      execute();
    }
    finally {
      StreamingContainer.eventloop.stop();
    }
    return status;
  }

  /**
   * Main run function for the application master
   *
   * @throws YarnException
   */
  @SuppressWarnings("SleepWhileInLoop")
  private void execute() throws YarnException, IOException
  {
    LOG.info("Starting ApplicationMaster");

    Credentials credentials = UserGroupInformation.getCurrentUser().getCredentials();
    LOG.info("number of tokens: {}", credentials.getAllTokens().size());
    Iterator<Token<?>> iter = credentials.getAllTokens().iterator();
    while (iter.hasNext()) {
      Token<?> token = iter.next();
      LOG.debug("token: " + token);
    }

    // Register self with ResourceManager
    RegisterApplicationMasterResponse response = amRmClient.registerApplicationMaster(appMasterHostname, 0, appMasterTrackingUrl);

    // Dump out information about cluster capability as seen by the resource manager
    int maxMem = response.getMaximumResourceCapability().getMemory();
    int maxVcores = response.getMaximumResourceCapability().getVirtualCores();
    LOG.info("Max mem {}m and vcores {} capabililty of resources in this cluster ", maxMem, maxVcores);

    // for locality relaxation fall back
    Map<StreamingContainerAgent.ContainerStartRequest, Integer> requestedResources = Maps.newHashMap();

    // Setup heartbeat emitter
    // TODO poll RM every now and then with an empty request to let RM know that we are alive
    // The heartbeat interval after which an AM is timed out by the RM is defined by a config setting:
    // RM_AM_EXPIRY_INTERVAL_MS with default defined by DEFAULT_RM_AM_EXPIRY_INTERVAL_MS
    // The allocate calls to the RM count as heartbeat so, for now, this additional heartbeat emitter
    // is not required.

    int loopCounter = -1;
    List<ContainerId> releasedContainers = new ArrayList<ContainerId>();
    int numTotalContainers = 0;
    // keep track of already requested containers to not request them again while waiting for allocation
    int numRequestedContainers = 0;
    int numReleasedContainers = 0;
    int nextRequestPriority = 0;
    ResourceRequestHandler resourceRequestor = new ResourceRequestHandler();

    YarnClient clientRMService = YarnClient.createYarnClient();

    try {
      // YARN-435
      // we need getClusterNodes to populate the initial node list,
      // subsequent updates come through the heartbeat response
      clientRMService.init(getConfig());
      clientRMService.start();
      resourceRequestor.updateNodeReports(clientRMService.getNodeReports());
    }
    catch (Exception e) {
      throw new RuntimeException("Failed to retrieve cluster nodes report.", e);
    }
    finally {
      clientRMService.stop();
    }

    // check for previously allocated containers
    // as of 2.2, containers won't survive AM restart, but this will change in the future - YARN-1490
    checkContainerStatus();
    FinalApplicationStatus finalStatus = FinalApplicationStatus.SUCCEEDED;
    int availableLicensedMemory = (licenseClient != null) ? 0 : Integer.MAX_VALUE;

    while (!appDone) {
      loopCounter++;

      Runnable r;
      while ((r = this.pendingTasks.poll()) != null) {
        r.run();
      }

      // log current state
      /*
       * LOG.info("Current application state: loop=" + loopCounter + ", appDone=" + appDone + ", total=" +
       * numTotalContainers + ", requested=" + numRequestedContainers + ", completed=" + numCompletedContainers +
       * ", failed=" + numFailedContainers + ", currentAllocated=" + this.allAllocatedContainers.size());
       */
      // Sleep before each loop when asking RM for containers
      // to avoid flooding RM with spurious requests when it
      // need not have any available containers
      try {
        sleep(1000);
      }
      catch (InterruptedException e) {
        LOG.info("Sleep interrupted " + e.getMessage());
      }

      // Setup request to be sent to RM to allocate containers
      List<ContainerRequest> containerRequests = new ArrayList<ContainerRequest>();
      // request containers for pending deploy requests
      if (!dnmgr.containerStartRequests.isEmpty()) {
        boolean requestResources = true;
        if (licenseClient != null) {
          // ensure enough memory is left to request new container
          licenseClient.reportAllocatedMemory((int) stats.getTotalMemoryAllocated());
          availableLicensedMemory = licenseClient.getRemainingEnforcementMB();
          Iterator<StreamingContainerAgent.ContainerStartRequest> it = dnmgr.containerStartRequests.iterator();
          int requiredMemory = 0;
          while (it.hasNext()) {
            requiredMemory += it.next().container.getRequiredMemoryMB();
          }
          if (requiredMemory > availableLicensedMemory) {
            LOG.warn("Insufficient licensed memory to request resources: required {}m available {}m", requiredMemory, availableLicensedMemory);
            if (licenseType == License.LicenseType.EVALUATION) {
              requestResources = false;
            }
          }
        }

        if (requestResources) {
          StreamingContainerAgent.ContainerStartRequest csr;
          while ((csr = dnmgr.containerStartRequests.poll()) != null) {
            if (csr.container.getRequiredMemoryMB() > maxMem) {
              LOG.warn("Container memory {}m above max threshold of cluster. Using max value {}m.", csr.container.getRequiredMemoryMB(), maxMem);
              csr.container.setRequiredMemoryMB(maxMem);
            }
            if (csr.container.getRequiredVCores() > maxVcores) {
              LOG.warn("Container vcores {} above max threshold of cluster. Using max value {}.", csr.container.getRequiredVCores(), maxVcores);
              csr.container.setRequiredVCores(maxVcores);
            }
            csr.container.setResourceRequestPriority(nextRequestPriority++);
            requestedResources.put(csr, loopCounter);
            containerRequests.add(resourceRequestor.createContainerRequest(csr, true));
          }
        }
      }

      if (!requestedResources.isEmpty()) {
        //resourceRequestor.clearNodeMapping();
        for (Map.Entry<StreamingContainerAgent.ContainerStartRequest, Integer> entry : requestedResources.entrySet()) {
          if ((loopCounter - entry.getValue()) > NUMBER_MISSED_HEARTBEATS) {
            entry.setValue(loopCounter);
            StreamingContainerAgent.ContainerStartRequest csr = entry.getKey();
            containerRequests.add(resourceRequestor.createContainerRequest(csr, false));
          }
        }
      }

      numTotalContainers += containerRequests.size();
      numRequestedContainers += containerRequests.size();
      AllocateResponse amResp = sendContainerAskToRM(containerRequests, releasedContainers);
      if (amResp.getAMCommand() != null) {
        LOG.info(" statement executed:{}", amResp.getAMCommand());
        switch (amResp.getAMCommand()) {
          case AM_RESYNC:
          case AM_SHUTDOWN:
            throw new YarnRuntimeException("Received the " + amResp.getAMCommand() + " command from RM");
          default:
            throw new YarnRuntimeException("Received the " + amResp.getAMCommand() + " command from RM");

        }
      }
      releasedContainers.clear();

      // CDH reporting incorrect resources, see SPOI-1846, YARN-1959. Workaround for now.
      int availableMemory = Math.min(amResp.getAvailableResources().getMemory(), availableLicensedMemory);
      LOG.debug(" available resources in cluster {}", availableMemory);
      availableMemory = (availableMemory == 0 ? availableLicensedMemory : availableMemory);

      //SPOI-2942: locking physical plan only when license type is evaluation
      if (this.licenseType == License.LicenseType.EVALUATION) {
        dnmgr.getPhysicalPlan().setAvailableResources(availableMemory);
      }

      // Retrieve list of allocated containers from the response
      List<Container> newAllocatedContainers = amResp.getAllocatedContainers();
      // LOG.info("Got response from RM for container ask, allocatedCnt=" + newAllocatedContainers.size());
      numRequestedContainers -= newAllocatedContainers.size();
      long timestamp = System.currentTimeMillis();
      for (Container allocatedContainer : newAllocatedContainers) {

        LOG.info("Got new container." + ", containerId=" + allocatedContainer.getId() + ", containerNode=" + allocatedContainer.getNodeId() + ", containerNodeURI=" + allocatedContainer.getNodeHttpAddress() + ", containerResourceMemory" + allocatedContainer.getResource().getMemory() + ", priority" + allocatedContainer.getPriority());
        // + ", containerToken" + allocatedContainer.getContainerToken().getIdentifier().toString());

        boolean alreadyAllocated = true;
        StreamingContainerAgent.ContainerStartRequest csr = null;
        for (Map.Entry<StreamingContainerAgent.ContainerStartRequest, Integer> entry : requestedResources.entrySet()) {
          if (entry.getKey().container.getResourceRequestPriority() == allocatedContainer.getPriority().getPriority()) {
            alreadyAllocated = false;
            csr = entry.getKey();
            break;
          }
        }

        if (alreadyAllocated) {
          LOG.info("Releasing {} as resource with priority {} was already assigned", allocatedContainer.getId(), allocatedContainer.getPriority());
          releasedContainers.add(allocatedContainer.getId());
          numReleasedContainers++;
          numRequestedContainers++;
          continue;
        }
        if (csr != null) {
          requestedResources.remove(csr);
        }

        // allocate resource to container
        ContainerResource resource = new ContainerResource(allocatedContainer.getPriority().getPriority(), allocatedContainer.getId().toString(), allocatedContainer.getNodeId().toString(), allocatedContainer.getResource().getMemory(), allocatedContainer.getResource().getVirtualCores(), allocatedContainer.getNodeHttpAddress());
        StreamingContainerAgent sca = dnmgr.assignContainer(resource, null);

        if (sca == null) {
          // allocated container no longer needed, add release request
          LOG.warn("Container {} allocated but nothing to deploy, going to release this container.", allocatedContainer.getId());
          releasedContainers.add(allocatedContainer.getId());
        }
        else {
          AllocatedContainer allocatedContainerHolder = new AllocatedContainer(allocatedContainer);
          this.allocatedContainers.put(allocatedContainer.getId().toString(), allocatedContainerHolder);
          ByteBuffer tokens = null;
          if (UserGroupInformation.isSecurityEnabled()) {
            UserGroupInformation ugi = UserGroupInformation.getLoginUser();
            Token<StramDelegationTokenIdentifier> delegationToken = allocateDelegationToken(ugi.getUserName(), heartbeatListener.getAddress());
            allocatedContainerHolder.delegationToken = delegationToken;
            //ByteBuffer tokens = LaunchContainerRunnable.getTokens(delegationTokenManager, heartbeatListener.getAddress());
            tokens = LaunchContainerRunnable.getTokens(ugi, delegationToken);
          }
          LaunchContainerRunnable launchContainer = new LaunchContainerRunnable(allocatedContainer, nmClient,sca, tokens);
          // Thread launchThread = new Thread(runnableLaunchContainer);
          // launchThreads.add(launchThread);
          // launchThread.start();
          launchContainer.run(); // communication with NMs is now async

          // record container start event
          StramEvent ev = new StramEvent.StartContainerEvent(allocatedContainer.getId().toString(), allocatedContainer.getNodeId().toString());
          ev.setTimestamp(timestamp);
          dnmgr.recordEventAsync(ev);
        }
      }

      // track node updates for future locality constraint allocations
      // TODO: it seems 2.0.4-alpha doesn't give us any updates
      resourceRequestor.updateNodeReports(amResp.getUpdatedNodes());

      // Check the completed containers
      List<ContainerStatus> completedContainers = amResp.getCompletedContainersStatuses();
      // LOG.debug("Got response from RM for container ask, completedCnt=" + completedContainers.size());
      for (ContainerStatus containerStatus : completedContainers) {
        LOG.info("Completed containerId=" + containerStatus.getContainerId() + ", state=" + containerStatus.getState() + ", exitStatus=" + containerStatus.getExitStatus() + ", diagnostics=" + containerStatus.getDiagnostics());

        // non complete containers should not be here
        assert (containerStatus.getState() == ContainerState.COMPLETE);

        AllocatedContainer allocatedContainer = allocatedContainers.remove(containerStatus.getContainerId().toString());
        if (allocatedContainer != null && allocatedContainer.delegationToken != null) {
          UserGroupInformation ugi = UserGroupInformation.getLoginUser();
          delegationTokenManager.cancelToken(allocatedContainer.delegationToken, ugi.getUserName());
        }
        int exitStatus = containerStatus.getExitStatus();
        if (0 != exitStatus) {
          if (allocatedContainer != null) {
            numFailedContainers.incrementAndGet();
          }
//          if (exitStatus == 1) {
//            // non-recoverable StreamingContainer failure
//            appDone = true;
//            finalStatus = FinalApplicationStatus.FAILED;
//            dnmgr.shutdownDiagnosticsMessage = "Unrecoverable failure " + containerStatus.getContainerId();
//            LOG.info("Exiting due to: {}", dnmgr.shutdownDiagnosticsMessage);
//          }
//          else {
          // Recoverable failure or process killed (externally or via stop request by AM)
          // also occurs when a container was released by the application but never assigned/launched
          LOG.debug("Container {} failed or killed.", containerStatus.getContainerId());
          dnmgr.scheduleContainerRestart(containerStatus.getContainerId().toString());
//          }
        }
        else {
          // container completed successfully
          numCompletedContainers.incrementAndGet();
          LOG.info("Container completed successfully." + ", containerId=" + containerStatus.getContainerId());
        }

        String containerIdStr = containerStatus.getContainerId().toString();
        dnmgr.removeContainerAgent(containerIdStr);

        // record container stop event
        StramEvent ev = new StramEvent.StopContainerEvent(containerIdStr, containerStatus.getExitStatus());
        ev.setReason(containerStatus.getDiagnostics());
        dnmgr.recordEventAsync(ev);
      }

      if (licenseClient != null) {
        if (!(amResp.getCompletedContainersStatuses().isEmpty() && amResp.getAllocatedContainers().isEmpty())) {
          // update license agent on allocated container changes
          licenseClient.reportAllocatedMemory((int) stats.getTotalMemoryAllocated());
        }
        availableLicensedMemory = licenseClient.getRemainingEnforcementMB();
      }

      if (dnmgr.forcedShutdown) {
        LOG.info("Forced shutdown due to {}", dnmgr.shutdownDiagnosticsMessage);
        finalStatus = FinalApplicationStatus.FAILED;
        appDone = true;
      }
      else if (allocatedContainers.isEmpty() && numRequestedContainers == 0 && dnmgr.containerStartRequests.isEmpty()) {
        LOG.debug("Exiting as no more containers are allocated or requested");
        finalStatus = FinalApplicationStatus.SUCCEEDED;
        appDone = true;
      }

      LOG.debug("Current application state: loop=" + loopCounter + ", appDone=" + appDone + ", total=" + numTotalContainers + ", requested=" + numRequestedContainers + ", released=" + numReleasedContainers + ", completed=" + numCompletedContainers + ", failed=" + numFailedContainers + ", currentAllocated=" + allocatedContainers.size());

      // monitor child containers
      dnmgr.monitorHeartbeat();
    }

    LOG.info("Application completed. Signalling finish to RM");
    FinishApplicationMasterRequest finishReq = Records.newRecord(FinishApplicationMasterRequest.class);
    finishReq.setFinalApplicationStatus(finalStatus);

    if (finalStatus != FinalApplicationStatus.SUCCEEDED) {
      String diagnostics = "Diagnostics." + ", total=" + numTotalContainers + ", completed=" + numCompletedContainers.get() + ", allocated=" + allocatedContainers.size() + ", failed=" + numFailedContainers.get();
      if (!StringUtils.isEmpty(dnmgr.shutdownDiagnosticsMessage)) {
        diagnostics += "\n";
        diagnostics += dnmgr.shutdownDiagnosticsMessage;
      }
      // YARN-208 - as of 2.0.1-alpha dropped by the RM
      finishReq.setDiagnostics(diagnostics);
      // expected termination of the master process
      // application status and diagnostics message are set above
    }
    LOG.info("diagnostics: " + finishReq.getDiagnostics());
    amRmClient.unregisterApplicationMaster(finishReq.getFinalApplicationStatus(), finishReq.getDiagnostics(), null);
  }

  private Token<StramDelegationTokenIdentifier> allocateDelegationToken(String username, InetSocketAddress address)
  {
    StramDelegationTokenIdentifier identifier = new StramDelegationTokenIdentifier(new Text(username), new Text(""), new Text(""));
    String service = address.getAddress().getHostAddress() + ":" + address.getPort();
    Token<StramDelegationTokenIdentifier> stramToken = new Token<StramDelegationTokenIdentifier>(identifier, delegationTokenManager);
    stramToken.setService(new Text(service));
    return stramToken;
  }

  /**
   * Check for containers that were allocated in a previous attempt.
   * If the containers are still alive, wait for them to check in via heartbeat.
   */
  private void checkContainerStatus()
  {
    Collection<StreamingContainerAgent> containers = this.dnmgr.getContainerAgents();
    for (StreamingContainerAgent ca : containers) {
      ContainerId containerId = ConverterUtils.toContainerId(ca.container.getExternalId());
      NodeId nodeId = ConverterUtils.toNodeId(ca.container.host);

      // put container back into the allocated list
      org.apache.hadoop.yarn.api.records.Token containerToken = null;
      Resource resource = Resource.newInstance(ca.container.getAllocatedMemoryMB(), ca.container.getAllocatedVCores());
      Priority priority = Priority.newInstance(ca.container.getResourceRequestPriority());
      Container yarnContainer = Container.newInstance(containerId, nodeId, ca.container.nodeHttpAddress, resource, priority, containerToken);
      this.allocatedContainers.put(containerId.toString(), new AllocatedContainer(yarnContainer));

      // check the status
      nmClient.getContainerStatusAsync(containerId, nodeId);
    }
  }

  /**
   * Ask RM to allocate given no. of containers to this Application Master
   *
   * @param containerRequests  Containers to ask for from RM
   * @param releasedContainers
   * @return Response from RM to AM with allocated containers
   * @throws YarnException
   */
  private AllocateResponse sendContainerAskToRM(List<ContainerRequest> containerRequests, List<ContainerId> releasedContainers) throws YarnException, IOException
  {
    if (containerRequests.size() > 0) {
      LOG.info("Asking RM for containers: " + containerRequests);
      for (ContainerRequest cr : containerRequests) {
        LOG.info("Requested container: {}", cr.toString());
        amRmClient.addContainerRequest(cr);
      }
    }

    for (ContainerId containerId : releasedContainers) {
      LOG.info("Released container, id={}", containerId.getId());
      amRmClient.releaseAssignedContainer(containerId);
    }

    for (String containerIdStr : dnmgr.containerStopRequests.values()) {
      AllocatedContainer allocatedContainer = this.allocatedContainers.get(containerIdStr);
      if (allocatedContainer != null && !allocatedContainer.stopRequested) {
        nmClient.stopContainerAsync(allocatedContainer.container.getId(), allocatedContainer.container.getNodeId());
        LOG.info("Requested stop container {}", containerIdStr);
        allocatedContainer.stopRequested = true;
      }
      dnmgr.containerStopRequests.remove(containerIdStr);
    }

    return amRmClient.allocate(0);
  }

  private class NMCallbackHandler implements NMClientAsync.CallbackHandler
  {
    NMCallbackHandler()
    {
    }

    @Override
    public void onContainerStopped(ContainerId containerId)
    {
      LOG.debug("Succeeded to stop Container {}", containerId);
    }

    @Override
    public void onContainerStatusReceived(ContainerId containerId, ContainerStatus containerStatus)
    {
      LOG.debug("Container Status: id={}, status={}", containerId, containerStatus);
      if (containerStatus.getState() != ContainerState.RUNNING) {
        recoverContainer(containerId);
      }
    }

    @Override
    public void onContainerStarted(ContainerId containerId, Map<String, ByteBuffer> allServiceResponse)
    {
      LOG.debug("Succeeded to start Container {}", containerId);
    }

    @Override
    public void onStartContainerError(ContainerId containerId, Throwable t)
    {
      LOG.error("Start container failed for: containerId={}", containerId, t);
    }

    @Override
    public void onGetContainerStatusError(ContainerId containerId, Throwable t)
    {
      LOG.error("Failed to query the status of {}", containerId, t);
      // if the NM is not reachable, consider container lost and recover (occurs during AM recovery)
      recoverContainer(containerId);
    }

    @Override
    public void onStopContainerError(ContainerId containerId, Throwable t)
    {
      LOG.warn("Failed to stop container {}", containerId, t);
      // container could not be stopped, we won't receive a stop event from AM heartbeat
      // short circuit and schedule recovery directly
      recoverContainer(containerId);
    }

    private void recoverContainer(final ContainerId containerId)
    {
      pendingTasks.add(new Runnable()
      {
        @Override
        public void run()
        {
          dnmgr.scheduleContainerRestart(containerId.toString());
          allocatedContainers.remove(containerId.toString());
        }

      });
    }

  }

  private class AllocatedContainer
  {
    final private Container container;
    private boolean stopRequested;
    private Token<StramDelegationTokenIdentifier> delegationToken;

    private AllocatedContainer(Container c)
    {
      container = c;
    }
  }

}
