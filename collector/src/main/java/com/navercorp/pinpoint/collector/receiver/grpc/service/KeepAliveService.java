/*
 * Copyright 2019 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.collector.receiver.grpc.service;

import com.navercorp.pinpoint.collector.service.async.AgentEventAsyncTaskService;
import com.navercorp.pinpoint.collector.service.async.AgentLifeCycleAsyncTaskService;
import com.navercorp.pinpoint.collector.service.async.AgentProperty;
import com.navercorp.pinpoint.collector.service.async.DefaultAgentProperty;
import com.navercorp.pinpoint.common.server.util.AgentEventType;
import com.navercorp.pinpoint.common.server.util.AgentLifeCycleState;
import com.navercorp.pinpoint.grpc.server.lifecycle.PingSession;
import com.navercorp.pinpoint.grpc.server.lifecycle.PingSessionRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.Objects;

/**
 * @author jaehong.kim
 */
public class KeepAliveService {
    private final Logger logger = LogManager.getLogger(this.getClass());
    private final AgentEventAsyncTaskService agentEventAsyncTask;
    private final AgentLifeCycleAsyncTaskService agentLifeCycleAsyncTask;
    private final PingSessionRegistry pingSessionRegistry;

    public KeepAliveService(AgentEventAsyncTaskService agentEventAsyncTask,
                            AgentLifeCycleAsyncTaskService agentLifeCycleAsyncTask,
                            PingSessionRegistry pingSessionRegistry) {
        this.agentEventAsyncTask = Objects.requireNonNull(agentEventAsyncTask, "agentEventAsyncTask");
        this.agentLifeCycleAsyncTask = Objects.requireNonNull(agentLifeCycleAsyncTask, "agentLifeCycleAsyncTask");
        this.pingSessionRegistry = Objects.requireNonNull(pingSessionRegistry, "pingSessionRegistry");
    }

    public void updateState() {
        final Collection<PingSession> lifecycles = pingSessionRegistry.values();
        for (PingSession lifecycle : lifecycles) {
            boolean closeState = false;
            AgentLifeCycleState agentLifeCycleState = AgentLifeCycleState.RUNNING;
            AgentEventType agentEventType = AgentEventType.AGENT_PING;
            updateState(lifecycle, closeState, agentLifeCycleState, agentEventType);
        }
    }

    private AgentProperty newChannelProperties(PingSession pingSession) {
        final String applicationName = pingSession.getApplicationName();
        final String agentId = pingSession.getAgentId();
        final long agentStartTime = pingSession.getAgentStartTime();
        short serviceType = pingSession.getServiceType();
        return new DefaultAgentProperty(applicationName, serviceType, agentId, agentStartTime, pingSession.getProperties());
    }

    public void updateState(PingSession lifecycle, ManagedAgentLifeCycle managedAgentLifeCycle) {
        boolean closeState = managedAgentLifeCycle.isClosedEvent();
        AgentLifeCycleState agentLifeCycleState = managedAgentLifeCycle.getMappedState();
        AgentEventType agentEventType = managedAgentLifeCycle.getMappedEvent();
        updateState(lifecycle, closeState, agentLifeCycleState, agentEventType);
    }

    public void updateState(PingSession pingSession, boolean closeState, AgentLifeCycleState agentLifeCycleState, AgentEventType agentEventType) {

        final long pingTimestamp = System.currentTimeMillis();
        final long socketId = pingSession.getSocketId();
        if (socketId == -1) {
            // TODO dump client ip for debug
            logger.warn("SocketId not exist. pingSession:{}", pingSession);
            // skip
            return;
        }

        try {
            final AgentProperty agentProperty = newChannelProperties(pingSession);
            long eventIdentifier = AgentLifeCycleAsyncTaskService.createEventIdentifier((int)socketId, (int) pingSession.nextEventIdAllocator());
            this.agentLifeCycleAsyncTask.handleLifeCycleEvent(agentProperty , pingTimestamp, agentLifeCycleState, eventIdentifier);
            this.agentEventAsyncTask.handleEvent(agentProperty, pingTimestamp, agentEventType);
        } catch (Exception e) {
            logger.warn("Failed to update state. closeState:{} lifeCycle={} {}/{}", closeState, pingSession, agentLifeCycleState, agentEventType, e);
        }
    }

    public void updateState(PingSession pingSession) {
        try {
            final AgentProperty agentProperty = newChannelProperties(pingSession);
            this.agentLifeCycleAsyncTask.handlePingEvent(agentProperty);
        } catch (Exception e) {
            logger.warn("Failed to update state. ping session={}", pingSession, e);
        }
    }

    public void destroy() {
        final Collection<PingSession> lifecycles = pingSessionRegistry.values();
        for (PingSession lifecycle : lifecycles) {
            updateState(lifecycle, ManagedAgentLifeCycle.CLOSED_BY_SERVER);
        }
    }
}