/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.carbon.event.processor.core.internal;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.databridge.commons.StreamDefinition;
import org.wso2.carbon.event.processor.core.*;
import org.wso2.carbon.event.processor.core.exception.ExecutionPlanConfigurationException;
import org.wso2.carbon.event.processor.core.exception.ExecutionPlanDependencyValidationException;
import org.wso2.carbon.event.processor.core.exception.StormDeploymentException;
import org.wso2.carbon.event.processor.core.internal.ds.EventProcessorValueHolder;
import org.wso2.carbon.event.processor.core.internal.listener.AbstractSiddhiInputEventDispatcher;
import org.wso2.carbon.event.processor.core.util.EventProcessorDistributedModeConstants;
import org.wso2.carbon.event.processor.core.internal.listener.SiddhiInputEventDispatcher;
import org.wso2.carbon.event.processor.core.internal.listener.SiddhiOutputStreamListener;
import org.wso2.carbon.event.processor.core.internal.storm.SiddhiStormInputEventDispatcher;
import org.wso2.carbon.event.processor.core.internal.storm.SiddhiStormOutputEventListener;
import org.wso2.carbon.event.processor.core.internal.storm.StormTopologyManager;
import org.wso2.carbon.event.processor.core.internal.util.EventProcessorConfigurationFilesystemInvoker;
import org.wso2.carbon.event.processor.core.internal.util.EventProcessorConstants;
import org.wso2.carbon.event.processor.core.internal.util.EventProcessorUtil;
import org.wso2.carbon.event.processor.core.internal.util.helper.EventProcessorHelper;
import org.wso2.carbon.event.processor.core.util.ExecutionPlanStatusHolder;
import org.wso2.carbon.event.processor.manager.core.config.DistributedConfiguration;
import org.wso2.carbon.event.processor.manager.core.config.ManagementModeInfo;
import org.wso2.carbon.event.processor.manager.core.config.Mode;
import org.wso2.carbon.event.stream.core.EventProducer;
import org.wso2.carbon.event.stream.core.SiddhiEventConsumer;
import org.wso2.carbon.event.stream.core.exception.EventStreamConfigurationException;
import org.wso2.siddhi.core.ExecutionPlanRuntime;
import org.wso2.siddhi.core.SiddhiManager;
import org.wso2.siddhi.core.stream.input.InputHandler;
import org.wso2.siddhi.query.api.annotation.Element;
import org.wso2.siddhi.query.api.definition.AbstractDefinition;
import org.wso2.siddhi.query.api.util.AnnotationHelper;
import org.wso2.siddhi.query.compiler.SiddhiCompiler;
import org.wso2.siddhi.query.compiler.exception.SiddhiParserException;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class CarbonEventProcessorService implements EventProcessorService {
    private static final Log log = LogFactory.getLog(CarbonEventProcessorService.class);
    // deployed query plans
    private Map<Integer, ConcurrentHashMap<String, ExecutionPlan>> tenantSpecificExecutionPlans;
    // not distinguishing between deployed vs failed here.
    private Map<Integer, List<ExecutionPlanConfigurationFile>> tenantSpecificExecutionPlanFiles;
    private ManagementModeInfo managementInfo;

//    private List<String> importDefinitions;              //old code block kept for reference
//    private List<String> exportDefinitions;              //old code block kept for reference

    public CarbonEventProcessorService() {
        tenantSpecificExecutionPlans = new ConcurrentHashMap<Integer, ConcurrentHashMap<String, ExecutionPlan>>();
        tenantSpecificExecutionPlanFiles = new ConcurrentHashMap<Integer, List<ExecutionPlanConfigurationFile>>();
    }


    @Override
    public void deployExecutionPlan(String executionPlan)
            throws ExecutionPlanDependencyValidationException, ExecutionPlanConfigurationException {
        //validate execution plan
        org.wso2.siddhi.query.api.ExecutionPlan parsedExecutionPlan = null;
        try {
            parsedExecutionPlan = SiddhiCompiler.parse(executionPlan);
            String executionPlanName = AnnotationHelper.getAnnotationElement(EventProcessorConstants.ANNOTATION_NAME_NAME, null, parsedExecutionPlan.getAnnotations()).getValue();

            if (!(isExecutionPlanAlreadyExist(executionPlanName))) {
                throw new ExecutionPlanConfigurationException(executionPlanName + " already registered as an execution in this tenant");
            }

            String repoPath = EventProcessorUtil.getAxisConfiguration().getRepository().getPath();
            File directory = new File(repoPath);
            if (!directory.exists()) {
                synchronized (repoPath.intern()) {
                    if (!directory.mkdir()) {
                        throw new ExecutionPlanConfigurationException("Cannot create directory to add tenant specific " +
                                "execution plan : " + executionPlanName);
                    }
                }
            }

            String eventProcessorConfigPath = directory.getAbsolutePath() + File.separator + EventProcessorConstants.EP_ELE_DIRECTORY;
            directory = new File(eventProcessorConfigPath);
            if (!directory.exists()) {
                synchronized (eventProcessorConfigPath.intern()) {
                    if (!directory.mkdir()) {
                        throw new ExecutionPlanConfigurationException("Cannot create directory " +
                                EventProcessorConstants.EP_ELE_DIRECTORY + " to add tenant specific  execution plan :" + executionPlanName);
                    }
                }
            }

            validateToRemoveInactiveExecutionPlanConfiguration(executionPlanName);
            EventProcessorConfigurationFilesystemInvoker.save(executionPlan, executionPlanName,
                    executionPlanName + EventProcessorConstants.SIDDHIQL_EXTENSION);

        } catch (SiddhiParserException re) {
            throw new ExecutionPlanConfigurationException("Couldn't parse execution plan: \n" + executionPlan + "\n");
        }
    }


    @Override
    public void undeployInactiveExecutionPlan(String filename)
            throws ExecutionPlanConfigurationException {
        EventProcessorConfigurationFilesystemInvoker.delete(filename);
    }

    @Override
    public void undeployActiveExecutionPlan(String planName) throws
            ExecutionPlanConfigurationException {
        EventProcessorConfigurationFilesystemInvoker.delete(getExecutionPlanConfigurationFileByPlanName(planName).getFileName());
    }

    public void editActiveExecutionPlan(String executionPlan,
                                        String executionPlanName)
            throws ExecutionPlanConfigurationException, ExecutionPlanDependencyValidationException {
        EventProcessorHelper.validateExecutionPlan(executionPlan);
        org.wso2.siddhi.query.api.ExecutionPlan parsedExecutionPlan = SiddhiCompiler.parse(executionPlan);
        String newExecutionPlanName = AnnotationHelper.getAnnotationElement(EventProcessorConstants.ANNOTATION_NAME_NAME, null, parsedExecutionPlan.getAnnotations()).getValue();
        if (!(newExecutionPlanName.equals(executionPlanName))) {
            if (!(isExecutionPlanAlreadyExist(newExecutionPlanName))) {
                throw new ExecutionPlanConfigurationException(newExecutionPlanName + " " +
                        "already registered as an execution in this tenant");
            }
        }
        if (executionPlanName != null && executionPlanName.length() > 0) {
            String fileName;
            ExecutionPlanConfigurationFile file = getExecutionPlanConfigurationFileByPlanName(executionPlanName);
            if (file == null) {
                fileName = executionPlanName + EventProcessorConstants.EP_CONFIG_FILE_EXTENSION_WITH_DOT;
            } else {
                fileName = file.getFileName();
            }
            EventProcessorConfigurationFilesystemInvoker.delete(fileName);
            EventProcessorConfigurationFilesystemInvoker.save(executionPlan, newExecutionPlanName, fileName);
        } else {
            throw new ExecutionPlanConfigurationException("Invalid configuration provided, No execution plan name.");
        }
    }

    public void editInactiveExecutionPlan(String executionPlan, String filename)
            throws ExecutionPlanConfigurationException, ExecutionPlanDependencyValidationException {
        EventProcessorHelper.validateExecutionPlan(executionPlan);
        org.wso2.siddhi.query.api.ExecutionPlan parsedExecutionPlan = SiddhiCompiler.parse(executionPlan);
        String newExecutionPlanName = AnnotationHelper.getAnnotationElement(EventProcessorConstants.ANNOTATION_NAME_NAME, null, parsedExecutionPlan.getAnnotations()).getValue();
        EventProcessorConfigurationFilesystemInvoker.delete(filename);
        EventProcessorConfigurationFilesystemInvoker.save(executionPlan, newExecutionPlanName, filename);
    }


    /**
     * Starts an execution plan runtime for the given (valid) execution plan.
     *
     * @param executionPlan Execution plan. It is assumed that the execution plan is a valid one when reaching this function.
     * @param isEditable    whether the execution plan is editable.
     * @throws ExecutionPlanConfigurationException
     */
    public void addExecutionPlan(String executionPlan, boolean isEditable) throws ExecutionPlanConfigurationException {
        //Assumption: executionPlan is valid

        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();

        SiddhiManager siddhiManager = EventProcessorValueHolder.getSiddhiManager();
        EventProcessorHelper.loadDataSourceConfiguration(siddhiManager);
        ExecutionPlanRuntime executionPlanRuntime = null;
        org.wso2.siddhi.query.api.ExecutionPlan parsedExecutionPlan = SiddhiCompiler.parse(executionPlan);

        ExecutionPlanConfiguration executionPlanConfiguration = new ExecutionPlanConfiguration();
        executionPlanConfiguration.setExecutionPlan(executionPlan);

        String executionPlanName = AnnotationHelper.getAnnotationElement(EventProcessorConstants.ANNOTATION_NAME_NAME, null, parsedExecutionPlan.getAnnotations()).getValue();   //Element is not null since the plan is a valid one.
        executionPlanConfiguration.setName(executionPlanName);

        Element executionPlanDescriptionElement = AnnotationHelper.getAnnotationElement(EventProcessorConstants.ANNOTATION_NAME_DESCRIPTION, null, parsedExecutionPlan.getAnnotations());
        if (executionPlanDescriptionElement != null) {
            String executionPlanDescription = executionPlanDescriptionElement.getValue();
            executionPlanConfiguration.setDescription(executionPlanDescription);
        } else {
            executionPlanConfiguration.setDescription("");
        }

        Element isTracingEnabledElement = AnnotationHelper.getAnnotationElement(EventProcessorConstants.ANNOTATION_NAME_TRACE, null, parsedExecutionPlan.getAnnotations());
        if (isTracingEnabledElement != null) {
            String isTracingEnabled = isTracingEnabledElement.getValue();
            executionPlanConfiguration.setTracingEnabled(Boolean.valueOf(isTracingEnabled));
        } else {
            executionPlanConfiguration.setTracingEnabled(false);
        }

        Element isStatsEnabledElement = AnnotationHelper.getAnnotationElement(EventProcessorConstants.ANNOTATION_NAME_STATISTICS, null, parsedExecutionPlan.getAnnotations());
        if (isStatsEnabledElement != null) {
            String isStatsEnabled = isStatsEnabledElement.getValue();
            executionPlanConfiguration.setStatisticsEnabled(Boolean.valueOf(isStatsEnabled));
        } else {
            executionPlanConfiguration.setStatisticsEnabled(false);
        }
        executionPlanConfiguration.setEditable(isEditable);

        ConcurrentHashMap<String, ExecutionPlan> tenantExecutionPlans = tenantSpecificExecutionPlans.get(tenantId);
        if (tenantExecutionPlans == null) {
            tenantExecutionPlans = new ConcurrentHashMap<String, ExecutionPlan>();
            tenantSpecificExecutionPlans.put(tenantId, tenantExecutionPlans);
        } else if (tenantExecutionPlans.get(executionPlanName) != null) {
            // if an execution plan with the same name already exists, we are not going to override it with this plan.
            throw new ExecutionPlanConfigurationException("Execution plan with the same name already exists. Please remove it and retry.");
        }

        //buidling Import/Export Map
        Map<String, String> importsMap = new HashMap<String, String>();   //<SiddhiStreamName, StreamID>
        Map<String, String> exportsMap = new HashMap<String, String>();   //<SiddhiStreamName, StreamID>
        for (Map.Entry<String, org.wso2.siddhi.query.api.definition.StreamDefinition> entry : parsedExecutionPlan.getStreamDefinitionMap().entrySet()) {

            String siddhiStreamName = entry.getKey();
            Element importElement = AnnotationHelper.getAnnotationElement(EventProcessorConstants.ANNOTATION_IMPORT, null, entry.getValue().getAnnotations());
            Element exportElement = AnnotationHelper.getAnnotationElement(EventProcessorConstants.ANNOTATION_EXPORT, null, entry.getValue().getAnnotations());

            if (importElement != null) {
                String streamId = importElement.getValue();
                importsMap.put(siddhiStreamName, streamId);
                String[] streamIdComponents = streamId.split(EventProcessorConstants.STREAM_SEPARATOR);
                String streamName = streamIdComponents[0];
                String streamVersion = streamIdComponents[1];
                executionPlanConfiguration.addImportedStream(new StreamConfiguration(streamName, streamVersion, siddhiStreamName));
            }
            if (exportElement != null) {
                String streamId = exportElement.getValue();
                exportsMap.put(siddhiStreamName, streamId);
                String[] streamIdComponents = streamId.split(EventProcessorConstants.STREAM_SEPARATOR);
                String streamName = streamIdComponents[0];
                String streamVersion = streamIdComponents[1];
                executionPlanConfiguration.addExportedStream(new StreamConfiguration(streamName, streamVersion, siddhiStreamName));
            }
        }

        Map<String, InputHandler> inputHandlerMap = new ConcurrentHashMap<String,
                InputHandler>(importsMap.size());


        List<String> importDefinitions;
        List<String> exportDefinitions;


        importDefinitions = new ArrayList<String>(executionPlanConfiguration.getImportedStreams().size());
        for (StreamConfiguration importedStreamConfiguration : executionPlanConfiguration.getImportedStreams()) {
            StreamDefinition streamDefinition;
            try {
                streamDefinition = EventProcessorValueHolder.getEventStreamService().getStreamDefinition
                        (importedStreamConfiguration.getStreamId());
                importDefinitions.add(EventProcessorUtil.getDefinitionString(streamDefinition,
                        importedStreamConfiguration.getSiddhiStreamName()));
            } catch (EventStreamConfigurationException e) {
                //ignored as this will not happen
            }
        }
        exportDefinitions = new ArrayList<String>(executionPlanConfiguration.getExportedStreams().size());
        for (StreamConfiguration exportedStreamConfiguration : executionPlanConfiguration.getExportedStreams()) {
            StreamDefinition streamDefinition;
            try {

                streamDefinition = EventProcessorValueHolder.getEventStreamService().getStreamDefinition(
                        exportedStreamConfiguration.getStreamId());
                exportDefinitions.add(EventProcessorUtil.getDefinitionString(streamDefinition,
                        exportedStreamConfiguration.getSiddhiStreamName()));
            } catch (EventStreamConfigurationException e) {
                //ignored as this will not happen
            }
        }

        /**
         * Section to handle query deployment
         */
        DistributedConfiguration stormDeploymentConfiguration = EventProcessorValueHolder.getStormDeploymentConfiguration();

        try {
            executionPlanRuntime = siddhiManager.createExecutionPlanRuntime(executionPlan);
        } catch (Exception e) {
            throw new ExecutionPlanConfigurationException("Invalid query specified, " + e.getMessage(), e);
        }

        if (managementInfo.getMode() == Mode.Distributed) {
            if (stormDeploymentConfiguration != null && stormDeploymentConfiguration.isManagerNode() && EventProcessorValueHolder
                    .getStormManagerServer().isStormManager()) {
                try {
                    EventProcessorValueHolder.getStormTopologyManager().submitTopology(executionPlanConfiguration, importDefinitions, exportDefinitions,
                            tenantId, stormDeploymentConfiguration.getTopologySubmitRetryInterval());
                } catch (StormDeploymentException e) {
                    throw new ExecutionPlanConfigurationException("Invalid distributed query specified, " + e.getMessage(), e);
                }
            }
        }

        for (Map.Entry<String, String> entry : importsMap.entrySet()) {
            inputHandlerMap.put(entry.getValue(), executionPlanRuntime.getInputHandler(entry.getKey()));
        }


        ExecutionPlan processorExecutionPlan = new ExecutionPlan(executionPlanName, executionPlanRuntime,
                executionPlanConfiguration);
        tenantExecutionPlans.put(executionPlanName, processorExecutionPlan);
        /**
         * Section to configure outputs
         */
        SiddhiStormOutputEventListener stormOutputListener = null;
        if (managementInfo.getMode() == Mode.Distributed && managementInfo.getDistributedConfiguration().isWorkerNode()) {
            stormOutputListener = new SiddhiStormOutputEventListener(executionPlanConfiguration, tenantId,
                    stormDeploymentConfiguration);
            processorExecutionPlan.addStormOutputListener(stormOutputListener);
        }

        for (Map.Entry<String, String> entry : exportsMap.entrySet()) {

            SiddhiOutputStreamListener streamCallback;

            streamCallback = new SiddhiOutputStreamListener(entry.getKey(),
                    entry.getValue(), executionPlanConfiguration, tenantId);

            if (managementInfo.getMode() == Mode.Distributed && stormDeploymentConfiguration != null && stormDeploymentConfiguration.isWorkerNode()) {
                try {
                    StreamDefinition databridgeDefinition = EventProcessorValueHolder.getEventStreamService()
                            .getStreamDefinition(entry.getValue());
                    org.wso2.siddhi.query.api.definition.StreamDefinition siddhiStreamDefinition = EventProcessorUtil
                            .convertToSiddhiStreamDefinition(databridgeDefinition, entry.getKey());
                    stormOutputListener.registerOutputStreamListener(siddhiStreamDefinition, streamCallback);
                } catch (EventStreamConfigurationException e) {
                    //ignored as this will not happen
                }
            } else {
                executionPlanRuntime.addCallback(entry.getKey(), streamCallback);
            }
            try {
                EventProcessorValueHolder.getEventStreamService().subscribe(streamCallback);
            } catch (EventStreamConfigurationException e) {
                //ignored as this will never happen
            }
            processorExecutionPlan.addProducer(streamCallback);
        }

        /**
         * Section to configure inputs
         */

        List<AbstractSiddhiInputEventDispatcher> inputEventDispatchers = new ArrayList<>();
        int cepReceiverCount = 0; //this is used in distributed mode only
        boolean isDistributedEnabledAndIsWroker = (managementInfo.getMode() == Mode.Distributed && stormDeploymentConfiguration != null && stormDeploymentConfiguration.isWorkerNode());
        for (Map.Entry<String, String> entry : importsMap.entrySet()) {
            InputHandler inputHandler = inputHandlerMap.get(entry.getValue());

            AbstractSiddhiInputEventDispatcher eventDispatcher;
            if (isDistributedEnabledAndIsWroker) {
                cepReceiverCount++;
                StreamDefinition streamDefinition = null;
                try {
                    streamDefinition = EventProcessorValueHolder.getEventStreamService().getStreamDefinition
                            (entry.getValue());
                } catch (EventStreamConfigurationException e) {
                    // Ignore as this would never happen
                }
                eventDispatcher = new SiddhiStormInputEventDispatcher(streamDefinition,
                        entry.getKey(), executionPlanConfiguration, tenantId,
                        stormDeploymentConfiguration);
            } else {
                eventDispatcher = new SiddhiInputEventDispatcher(entry.getValue(),
                        inputHandler, executionPlanConfiguration, tenantId);
            }
            inputEventDispatchers.add(eventDispatcher);

        }

        if(isDistributedEnabledAndIsWroker){
            updateCepReceiverCountInExecutionPlanStatusHolder(cepReceiverCount, StormTopologyManager.getTopologyName(executionPlanName, tenantId));
        }

        if (executionPlanRuntime != null) {
            executionPlanRuntime.start();
        }

        for (AbstractSiddhiInputEventDispatcher eventDispatcher : inputEventDispatchers) {
            try {
                EventProcessorValueHolder.getEventStreamService().subscribe(eventDispatcher);
                processorExecutionPlan.addConsumer(eventDispatcher);
            } catch (EventStreamConfigurationException e) {
                //ignored as this will never happen
            }
        }

        if (EventProcessorValueHolder.getPersistenceConfiguration() != null) {
            executionPlanRuntime.restoreLastRevision();
        }


    }

    /**
     * Used, only in distributed mode, for updating the execution plan status in the UI
     * @param cepReceiverCount
     * @param stormTopologyName
     */
    private void updateCepReceiverCountInExecutionPlanStatusHolder(int cepReceiverCount, String stormTopologyName) {    //todo: rename this to update..TotalCount
        String keyExecutionPlanStatusHolder = EventProcessorDistributedModeConstants.STORM_STATUS_MAP + "." + stormTopologyName;
        HazelcastInstance hazelcastInstance = EventProcessorValueHolder.getHazelcastInstance();
        IMap<String,ExecutionPlanStatusHolder> executionPlanStatusHolderIMap = hazelcastInstance.getMap(EventProcessorDistributedModeConstants.STORM_STATUS_MAP);
        try {
            if (executionPlanStatusHolderIMap.tryLock(keyExecutionPlanStatusHolder, EventProcessorDistributedModeConstants.LOCK_TIMEOUT, TimeUnit.SECONDS)){
                try {
                    ExecutionPlanStatusHolder executionPlanStatusHolder =
                            executionPlanStatusHolderIMap.get(stormTopologyName);
                    if(executionPlanStatusHolder == null){
                        executionPlanStatusHolder = new ExecutionPlanStatusHolder();
                        executionPlanStatusHolderIMap.putIfAbsent(stormTopologyName, executionPlanStatusHolder);
                    }
                    executionPlanStatusHolder.setRequiredCepReceiversCount(cepReceiverCount);
                    executionPlanStatusHolderIMap.replace(stormTopologyName, executionPlanStatusHolder);
                } finally {
                    executionPlanStatusHolderIMap.unlock(keyExecutionPlanStatusHolder);
                }
            } else {
                log.error(EventProcessorDistributedModeConstants.ERROR_LOCK_ACQUISITION_FAILED_FOR_REQUIRED_CEP_RECEIVERS);
            }
        } catch (InterruptedException e) {
            log.error(EventProcessorDistributedModeConstants.ERROR_LOCK_ACQUISITION_FAILED_FOR_REQUIRED_CEP_RECEIVERS, e);
        }
    }

    public List<StreamDefinition> getSiddhiStreams(String executionPlan) {
        SiddhiManager siddhiManager = EventProcessorValueHolder.getSiddhiManager();
        EventProcessorHelper.loadDataSourceConfiguration(siddhiManager);
        ExecutionPlanRuntime executionPlanRuntime = siddhiManager.createExecutionPlanRuntime(executionPlan);
        Collection<AbstractDefinition> streamDefinitions = executionPlanRuntime.getStreamDefinitionMap().values();
        List<StreamDefinition> databridgeStreamDefinitions = new ArrayList<StreamDefinition>(streamDefinitions.size());
        for (AbstractDefinition siddhiStreamDef : streamDefinitions) {
            StreamConfiguration streamConfig = new StreamConfiguration(siddhiStreamDef.getId());
            StreamDefinition databridgeStreamDef = EventProcessorUtil.convertToDatabridgeStreamDefinition(
                    (org.wso2.siddhi.query.api.definition.StreamDefinition) siddhiStreamDef, streamConfig);
            databridgeStreamDefinitions.add(databridgeStreamDef);
        }
        executionPlanRuntime.shutdown();
        return databridgeStreamDefinitions;
    }


    @Override
    public boolean isDistributedProcessingEnabled() {
        if(managementInfo.getMode() == Mode.Distributed){
            return true;
        }
        return false;
    }

    @Override
    public Map<String, String> getAllExecutionPlanStatusesInStorm(){
        HazelcastInstance hazelcastInstance = EventProcessorValueHolder.getHazelcastInstance();
        IMap<String,ExecutionPlanStatusHolder> executionPlanStatusHolderIMap = hazelcastInstance.getMap(EventProcessorDistributedModeConstants.STORM_STATUS_MAP);

        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        Map<String, ExecutionPlan> executionPlanMap = tenantSpecificExecutionPlans.get(tenantId);

        Set<String> executionPlanNames = executionPlanMap.keySet();

        Map<String, String> executionPlanStatuses = new HashMap<String, String>();  //key:ExecutionPlanName, Value:Status Explanation.
        String status;

        for (String executionPlanName: executionPlanNames){
            status = executionPlanStatusHolderIMap.get(StormTopologyManager.getTopologyName(executionPlanName, tenantId)).getExecutionPlanStatus();
            executionPlanStatuses.put(executionPlanName,status);
        }
        return executionPlanStatuses;
    }

    public void validateExecutionPlan(String executionPlan)
            throws ExecutionPlanConfigurationException, ExecutionPlanDependencyValidationException {
        EventProcessorHelper.validateExecutionPlan(executionPlan);
    }

    public ManagementModeInfo getManagementInfo() {
        return managementInfo;
    }

    public void setManagementInfo(ManagementModeInfo managementInfo) {
        this.managementInfo = managementInfo;
    }

    public void notifyServiceAvailability(String serviceId) {
        for (Integer tenantId : tenantSpecificExecutionPlanFiles.keySet()) {
            try {
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantId(tenantId);
                PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain(true);
                activateInactiveExecutionPlanConfigurations(ExecutionPlanConfigurationFile.Status.WAITING_FOR_OSGI_SERVICE, serviceId);
            } catch (ExecutionPlanConfigurationException e) {
                log.error("Error while redeploying distributed execution plans.", e);
            } finally {
                PrivilegedCarbonContext.endTenantFlow();
            }
        }
    }

    private void removeExecutionPlanConfiguration(String name) {
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        Map<String, ExecutionPlan> executionPlanMap = tenantSpecificExecutionPlans.get(tenantId);
        if (executionPlanMap != null && executionPlanMap.containsKey(name)) {
            ExecutionPlan executionPlan = executionPlanMap.remove(name);
            executionPlan.shutdown();

            ExecutionPlanConfiguration executionPlanConfiguration = executionPlan.getExecutionPlanConfiguration();

            DistributedConfiguration stormDeploymentConfig = EventProcessorValueHolder.getStormDeploymentConfiguration();
            if (managementInfo.getMode() == Mode.Distributed && stormDeploymentConfig != null && stormDeploymentConfig.isManagerNode() &&
                    EventProcessorValueHolder.getStormManagerServer().isStormManager()) {
                try {
                    removeExecutionPlanStatusHolder(executionPlanConfiguration.getName(), tenantId);
                    EventProcessorValueHolder.getStormTopologyManager().killTopology(executionPlanConfiguration.getName(), tenantId);

                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }

            // releasing junction listeners.
            for (SiddhiEventConsumer eventConsumer : executionPlan.getSiddhiEventConsumers()) {
                EventProcessorValueHolder.getEventStreamService().unsubscribe(eventConsumer);
            }

            for (EventProducer eventProducer : executionPlan.getEventProducers()) {
                EventProcessorValueHolder.getEventStreamService().unsubscribe(eventProducer);
            }

        }

    }

    public void removeExecutionPlanStatusHolder(String executionPlanName, int tenantId){
        HazelcastInstance hazelcastInstance = EventProcessorValueHolder.getHazelcastInstance();
        IMap<String,ExecutionPlanStatusHolder> executionPlanStatusHolderIMap =
                hazelcastInstance.getMap(EventProcessorDistributedModeConstants.STORM_STATUS_MAP);

        String stormTopologyName = StormTopologyManager.getTopologyName(executionPlanName, tenantId);
        ExecutionPlanStatusHolder executionPlanStatusHolder =
                executionPlanStatusHolderIMap.get(stormTopologyName);

        executionPlanStatusHolderIMap.remove(stormTopologyName, executionPlanStatusHolder);
    }

    public void addExecutionPlanConfigurationFile(ExecutionPlanConfigurationFile configurationFile) {
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        List<ExecutionPlanConfigurationFile> executionPlanConfigurationFiles = tenantSpecificExecutionPlanFiles.get(tenantId);
        if (executionPlanConfigurationFiles == null) {
            executionPlanConfigurationFiles = new CopyOnWriteArrayList<>();
            tenantSpecificExecutionPlanFiles.put(tenantId, executionPlanConfigurationFiles);
        }
        executionPlanConfigurationFiles.add(configurationFile);
    }

    /**
     * Just removes the configuration file
     *
     * @param fileName the filename of the {@link ExecutionPlanConfigurationFile} to be removed
     */
    public void removeExecutionPlanConfigurationFile(String fileName) {
        List<ExecutionPlanConfigurationFile> executionPlanConfigurationFiles = tenantSpecificExecutionPlanFiles
                .get(PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId());
        for (ExecutionPlanConfigurationFile configurationFile : executionPlanConfigurationFiles) {
            if (new File(configurationFile.getFileName()).getName().equals(fileName)) {
                if (configurationFile.getStatus().equals(ExecutionPlanConfigurationFile.Status.DEPLOYED)) {
                    removeExecutionPlanConfiguration(configurationFile.getExecutionPlanName());
                }
                executionPlanConfigurationFiles.remove(configurationFile);
                return;
            }
        }
    }

    public String getActiveExecutionPlan(String planName)
            throws ExecutionPlanConfigurationException {
        ExecutionPlanConfigurationFile configFile = getExecutionPlanConfigurationFileByPlanName(planName);
        if (configFile == null) {
            throw new ExecutionPlanConfigurationException("Configuration file for " + planName + "doesn't exist.");
        }
        return EventProcessorConfigurationFilesystemInvoker.readExecutionPlanConfigFile(configFile.getFileName());
    }

    public String getInactiveExecutionPlan(String filename)
            throws ExecutionPlanConfigurationException {
        return EventProcessorConfigurationFilesystemInvoker.readExecutionPlanConfigFile(filename);
    }

    @Override
    public Map<String, ExecutionPlanConfiguration> getAllActiveExecutionConfigurations() {
        Map<String, ExecutionPlanConfiguration> configurationMap = new HashMap<String, ExecutionPlanConfiguration>();
        Map<String, ExecutionPlan> executionPlanMap = tenantSpecificExecutionPlans
                .get(PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId());
        if (executionPlanMap != null) {
            for (Map.Entry<String, ExecutionPlan> entry : executionPlanMap.entrySet()) {
                configurationMap.put(entry.getKey(), entry.getValue().getExecutionPlanConfiguration());
            }
        }
        return configurationMap;
    }

    @Override
    public Map<String, ExecutionPlanConfiguration> getAllExportedStreamSpecificActiveExecutionConfigurations(String streamId) {
        Map<String, ExecutionPlanConfiguration> configurationMap = new HashMap<String, ExecutionPlanConfiguration>();
        Map<String, ExecutionPlan> executionPlanMap = tenantSpecificExecutionPlans
                .get(PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId());
        if (executionPlanMap != null) {
            for (Map.Entry<String, ExecutionPlan> entry : executionPlanMap.entrySet()) {

                List<StreamConfiguration> streamConfigurationList = entry.getValue().getExecutionPlanConfiguration().getExportedStreams();
                for (StreamConfiguration streamConfiguration : streamConfigurationList) {
                    String streamNameWithVersion = streamConfiguration.getName() + ":" + streamConfiguration.getVersion();
                    if (streamNameWithVersion.equals(streamId)) {
                        configurationMap.put(entry.getKey(), entry.getValue().getExecutionPlanConfiguration());
                    }
                }
            }
        }
        return configurationMap;
    }

    @Override
    public Map<String, ExecutionPlanConfiguration> getAllImportedStreamSpecificActiveExecutionConfigurations(String streamId) {
        Map<String, ExecutionPlanConfiguration> configurationMap = new HashMap<String, ExecutionPlanConfiguration>();
        Map<String, ExecutionPlan> executionPlanMap = tenantSpecificExecutionPlans
                .get(PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId());
        if (executionPlanMap != null) {
            for (Map.Entry<String, ExecutionPlan> entry : executionPlanMap.entrySet()) {

                List<StreamConfiguration> streamConfigurationList = entry.getValue().getExecutionPlanConfiguration()
                        .getImportedStreams();
                for (StreamConfiguration streamConfiguration : streamConfigurationList) {
                    String streamNameWithVersion = streamConfiguration.getName() + ":" + streamConfiguration.getVersion();
                    if (streamNameWithVersion.equals(streamId)) {
                        configurationMap.put(entry.getKey(), entry.getValue().getExecutionPlanConfiguration());
                    }
                }
            }
        }
        return configurationMap;
    }

    @Override
    public ExecutionPlanConfiguration getActiveExecutionPlanConfiguration(String planName) {
        Map<String, ExecutionPlan> executionPlanMap = tenantSpecificExecutionPlans
                .get(PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId());
        if (executionPlanMap != null) {
            ExecutionPlan executionPlan = executionPlanMap.get(planName);
            if (executionPlan != null) {
                return executionPlan.getExecutionPlanConfiguration();
            }
        }
        return null;
    }


    public ExecutionPlan getActiveExecutionPlan(String name, int tenantId) {
        Map<String, ExecutionPlan> executionPlanMap = tenantSpecificExecutionPlans.get(tenantId);
        if (executionPlanMap != null) {
            return executionPlanMap.get(name);
        }
        return null;
    }

    @Override
    public List<ExecutionPlanConfigurationFile> getAllInactiveExecutionPlanConfiguration() {
        List<ExecutionPlanConfigurationFile> executionPlanConfigurationFiles = this.tenantSpecificExecutionPlanFiles
                .get(PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId());

        List<ExecutionPlanConfigurationFile> files = new ArrayList<ExecutionPlanConfigurationFile>();
        if (executionPlanConfigurationFiles != null) {
            for (ExecutionPlanConfigurationFile configFile : executionPlanConfigurationFiles) {
                if (configFile.getStatus() == ExecutionPlanConfigurationFile.Status.ERROR || configFile.getStatus()
                        == ExecutionPlanConfigurationFile.Status.WAITING_FOR_DEPENDENCY || configFile.getStatus() ==
                        ExecutionPlanConfigurationFile.Status.WAITING_FOR_OSGI_SERVICE) {
                    files.add(configFile);
                }
            }
        }
        return files;
    }

    @Override
    public void setTracingEnabled(String executionPlanName, boolean isEnabled)
            throws ExecutionPlanConfigurationException {
        Map<String, ExecutionPlan> executionPlans = tenantSpecificExecutionPlans
                .get(PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId());
        if (executionPlans != null) {
            ExecutionPlan processorExecutionPlan = executionPlans.get(executionPlanName);
            ExecutionPlanConfiguration executionPlanConfiguration = processorExecutionPlan.getExecutionPlanConfiguration();
            executionPlanConfiguration.setTracingEnabled(isEnabled);
            String executionPlan = executionPlanConfiguration.getExecutionPlan();
            String newExecutionPlan = EventProcessorHelper.setExecutionPlanAnnotationName(executionPlan, EventProcessorConstants.ANNOTATION_NAME_TRACE, isEnabled);
            executionPlanConfiguration.setExecutionPlan(newExecutionPlan);
            ExecutionPlanConfigurationFile configFile = getExecutionPlanConfigurationFileByPlanName(executionPlanName);
            String fileName = configFile.getFileName();
            EventProcessorConfigurationFilesystemInvoker.delete(fileName);
            EventProcessorConfigurationFilesystemInvoker.save(newExecutionPlan, executionPlanName, fileName);
        }
    }

    @Override
    public void setStatisticsEnabled(String executionPlanName, boolean isEnabled)
            throws ExecutionPlanConfigurationException {
        Map<String, ExecutionPlan> processorExecutionPlans = tenantSpecificExecutionPlans
                .get(PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId());
        if (processorExecutionPlans != null) {
            ExecutionPlan processorExecutionPlan = processorExecutionPlans.get(executionPlanName);
            ExecutionPlanConfiguration executionPlanConfiguration = processorExecutionPlan.getExecutionPlanConfiguration();
            executionPlanConfiguration.setStatisticsEnabled(isEnabled);
            String executionPlan = executionPlanConfiguration.getExecutionPlan();
            String newExecutionPlan = EventProcessorHelper.setExecutionPlanAnnotationName(executionPlan,
                    EventProcessorConstants.ANNOTATION_NAME_STATISTICS, isEnabled);
            executionPlanConfiguration.setExecutionPlan(newExecutionPlan);
            ExecutionPlanConfigurationFile configFile = getExecutionPlanConfigurationFileByPlanName(executionPlanName);
            String fileName = configFile.getFileName();
            EventProcessorConfigurationFilesystemInvoker.delete(fileName);
            EventProcessorConfigurationFilesystemInvoker.save(newExecutionPlan, executionPlanName, fileName);
        }
    }

    /**
     * Activate Inactive Execution Plan Configurations
     *
     * @param resolvedDependencyId the id of the dependency that was resolved which resulted in triggering this method call
     */
    public void activateInactiveExecutionPlanConfigurations(
            ExecutionPlanConfigurationFile.Status status, String resolvedDependencyId)
            throws ExecutionPlanConfigurationException {

        List<ExecutionPlanConfigurationFile> reloadFileList = new ArrayList<ExecutionPlanConfigurationFile>();
        if (tenantSpecificExecutionPlanFiles != null && tenantSpecificExecutionPlanFiles.size() > 0) {
            List<ExecutionPlanConfigurationFile> executionPlanConfigurationFiles = tenantSpecificExecutionPlanFiles
                    .get(PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId());

            if (executionPlanConfigurationFiles != null) {
                for (ExecutionPlanConfigurationFile executionPlanConfigurationFile : executionPlanConfigurationFiles) {
                    if ((executionPlanConfigurationFile.getStatus().equals(status)) && resolvedDependencyId
                            .equalsIgnoreCase(executionPlanConfigurationFile.getDependency())) {
                        reloadFileList.add(executionPlanConfigurationFile);
                    }
                }
            }
        }
        for (ExecutionPlanConfigurationFile executionPlanConfigurationFile : reloadFileList) {
            try {
                EventProcessorConfigurationFilesystemInvoker.reload(executionPlanConfigurationFile.getFilePath());
            } catch (ExecutionPlanConfigurationException e) {
                log.error("Exception occurred while trying to deploy the Execution Plan configuration file : " + new
                        File(executionPlanConfigurationFile.getFileName()).getName() + "," + e.getMessage(), e);
            }
        }

    }

    public void deactivateActiveExecutionPlanConfigurations(String streamId) {

        List<String> toDeactivateExecutionPlan = new ArrayList<String>();
        Map<String, ExecutionPlan> executionPlanMap = tenantSpecificExecutionPlans
                .get(PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId());
        if (executionPlanMap != null) {
            for (ExecutionPlan executionPlan : executionPlanMap.values()) {
                boolean done = false;
                for (EventProducer eventProducer : executionPlan.getEventProducers()) {
                    if (eventProducer.getStreamId().equals(streamId)) {
                        toDeactivateExecutionPlan.add(executionPlan.getName());
                        done = true;
                        break;
                    }
                }

                if (!done) {
                    for (SiddhiEventConsumer eventConsumer : executionPlan.getSiddhiEventConsumers()) {
                        if (eventConsumer.getStreamId().equals(streamId)) {
                            toDeactivateExecutionPlan.add(executionPlan.getName());
                            break;
                        }
                    }
                }
            }
        }
        if (toDeactivateExecutionPlan.size() > 0) {
            for (String name : toDeactivateExecutionPlan) {
                ExecutionPlanConfigurationFile executionPlanConfigurationFile = getExecutionPlanConfigurationFileByPlanName(name);
                try {
                    EventProcessorConfigurationFilesystemInvoker.reload(executionPlanConfigurationFile.getFilePath());
                } catch (Exception e) {
                    log.error("Exception occurred while trying to deploy the Execution Plan configuration file : " +
                            new File(executionPlanConfigurationFile.getFileName()).getName());
                }
            }
        }
    }

    // gets file by name.
    private ExecutionPlanConfigurationFile getExecutionPlanConfigurationFileByPlanName(String name) {
        List<ExecutionPlanConfigurationFile> executionPlanConfigurationFiles = tenantSpecificExecutionPlanFiles
                .get(PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId());
        if (executionPlanConfigurationFiles != null) {
            for (ExecutionPlanConfigurationFile file : executionPlanConfigurationFiles) {
                if (name.equals(file.getExecutionPlanName()) && file.getStatus().equals(ExecutionPlanConfigurationFile.Status.DEPLOYED)) {
                    return file;
                }
            }
        }
        return null;
    }

    private void validateToRemoveInactiveExecutionPlanConfiguration(String executionPlanName)
            throws ExecutionPlanConfigurationException {
        String fileName = executionPlanName + EventProcessorConstants.EP_CONFIG_FILE_EXTENSION_WITH_DOT;
        List<ExecutionPlanConfigurationFile> executionPlanConfigurationFiles = tenantSpecificExecutionPlanFiles
                .get(PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId());
        if (executionPlanConfigurationFiles != null) {
            for (ExecutionPlanConfigurationFile executionPlanConfigurationFile : executionPlanConfigurationFiles) {
                if ((executionPlanConfigurationFile.getFileName().equals(fileName))) {
                    if (!(executionPlanConfigurationFile.getStatus().equals(ExecutionPlanConfigurationFile.Status.DEPLOYED))) {
                        EventProcessorConfigurationFilesystemInvoker.delete(fileName);
                        break;
                    }
                }
            }
        }
    }

    private boolean isExecutionPlanAlreadyExist(String executionPlanName)
            throws ExecutionPlanConfigurationException {

        Map<String, ExecutionPlanConfiguration> executionPlanConfigurationMap;
        executionPlanConfigurationMap = getAllActiveExecutionConfigurations();
        if (executionPlanConfigurationMap != null) {
            for (String existingExecutionPlanName : executionPlanConfigurationMap.keySet()) {
                if (executionPlanName.equalsIgnoreCase(existingExecutionPlanName)) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean isExecutionPlanFileAlreadyExist(String executionPlanFileName)
            throws ExecutionPlanConfigurationException {

        if (tenantSpecificExecutionPlanFiles.size() > 0) {
            List<ExecutionPlanConfigurationFile> executionPlanConfigurationFileList = tenantSpecificExecutionPlanFiles
                    .get(PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId());
            if (executionPlanConfigurationFileList != null) {
                for (ExecutionPlanConfigurationFile executionPlanConfigurationFile : executionPlanConfigurationFileList) {
                    if ((executionPlanConfigurationFile.getFileName().equals(executionPlanFileName))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public Map<Integer, ConcurrentHashMap<String, ExecutionPlan>> getTenantSpecificExecutionPlans() {
        return tenantSpecificExecutionPlans;
    }

    public void shutdown() {

        for (Map.Entry<Integer, ConcurrentHashMap<String, ExecutionPlan>> executionPlans : tenantSpecificExecutionPlans.entrySet()) {
            for (ExecutionPlan executionPlan : executionPlans.getValue().values()) {
                try {
                    executionPlan.shutdown();
                } catch (RuntimeException e) {
                    log.error("Error in shutting down ExecutionPlan '" + executionPlan.getName() + "' of tenant '" + executionPlans.getKey() + "'," + e.getMessage(), e);
                }
            }
        }
        log.info("Successfully shutdown ExecutionPlans");
    }

}
