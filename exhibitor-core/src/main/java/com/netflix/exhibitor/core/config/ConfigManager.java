/*
 *
 *  Copyright 2011 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package com.netflix.exhibitor.core.config;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.netflix.exhibitor.core.Exhibitor;
import com.netflix.exhibitor.core.activity.Activity;
import com.netflix.exhibitor.core.activity.QueueGroups;
import com.netflix.exhibitor.core.activity.RepeatingActivity;
import com.netflix.exhibitor.core.state.InstanceState;
import com.netflix.exhibitor.core.state.InstanceStateTypes;
import com.netflix.exhibitor.core.state.RemoteInstanceRequest;
import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class ConfigManager implements Closeable
{
    private final Exhibitor exhibitor;
    private final ConfigProvider provider;
    private final RepeatingActivity repeatingActivity;
    private final AtomicReference<LoadedInstanceConfig> config = new AtomicReference<LoadedInstanceConfig>();
    private final Set<ConfigListener> configListeners = Sets.newSetFromMap(Maps.<ConfigListener, Boolean>newConcurrentMap());
    private final AtomicReference<RollingConfigAdvanceAttempt> rollingConfigAdvanceAttempt = new AtomicReference<RollingConfigAdvanceAttempt>(null);

    @VisibleForTesting
    final static int        MAX_ATTEMPTS = 4;

    public ConfigManager(Exhibitor exhibitor, ConfigProvider provider, int checkMs) throws Exception
    {
        this.exhibitor = exhibitor;
        this.provider = provider;

        Activity    activity = new Activity()
        {
            @Override
            public void completed(boolean wasSuccessful)
            {
            }

            @Override
            public Boolean call() throws Exception
            {
                doWork();
                return true;
            }
        };
        repeatingActivity = new RepeatingActivity(exhibitor.getLog(), exhibitor.getActivityQueue(), QueueGroups.MAIN, activity, checkMs);

        config.set(provider.loadConfig());
    }

    public void   start()
    {
        repeatingActivity.start();
    }

    @Override
    public void close() throws IOException
    {
        repeatingActivity.close();
    }

    public InstanceConfig getConfig()
    {
        return getCollection().getConfigForThisInstance(exhibitor.getThisJVMHostname());
    }

    public boolean              isRolling()
    {
        return getCollection().isRolling();
    }

    public RollingConfigState   getRollingConfigState()
    {
        return getCollection().getRollingConfigState();
    }

    /**
     * Add a listener for config changes
     *
     * @param listener listener
     */
    public void addConfigListener(ConfigListener listener)
    {
        configListeners.add(listener);
    }

    public void writeHeartbeat() throws Exception
    {
        provider.writeInstanceHeartbeat(exhibitor.getThisJVMHostname());
    }

    public long getLastHeartbeatForInstance(String instance) throws Exception
    {
        return provider.getLastHeartbeatForInstance(instance);
    }

    public enum CancelMode
    {
        ROLLBACK,
        FORCE_COMMIT
    }

    public PseudoLock       newConfigBasedLock() throws Exception
    {
        return provider.newPseudoLock();
    }

    public synchronized void     cancelRollingConfig(CancelMode mode) throws Exception
    {
        ConfigCollection localConfig = getCollection();
        if ( localConfig.isRolling() )
        {
            rollingConfigAdvanceAttempt.set(null);

            InstanceConfig          newConfig = (mode == CancelMode.ROLLBACK) ? localConfig.getRootConfig() : localConfig.getRollingConfig();
            ConfigCollection        newCollection = new ConfigCollectionImpl(newConfig, null);
            internalUpdateConfig(newCollection);
        }
    }

    public synchronized void     checkRollingConfig(InstanceState instanceState) throws Exception
    {
        ConfigCollection localConfig = getCollection();
        if ( localConfig.isRolling() )
        {
            RollingReleaseState     state = new RollingReleaseState(instanceState, localConfig);
            if ( state.getCurrentRollingHostname().equals(exhibitor.getThisJVMHostname()) )
            {
                if ( state.serverListHasSynced() && (instanceState.getState() == InstanceStateTypes.SERVING) )
                {
                    advanceRollingConfig(localConfig);
                }
            }
        }
    }

    public synchronized boolean startRollingConfig(final InstanceConfig newConfig) throws Exception
    {
        ConfigCollection        localConfig = getCollection();
        if ( localConfig.isRolling() )
        {
            return false;
        }

        InstanceConfig          currentConfig = getCollection().getRootConfig();
        RollingHostNamesBuilder builder = new RollingHostNamesBuilder(currentConfig, newConfig, exhibitor.getLog());

        rollingConfigAdvanceAttempt.set(null);

        ConfigCollection        newCollection = new ConfigCollectionImpl(currentConfig, newConfig, builder.getRollingHostNames(), 0);
        return advanceOrStartRollingConfig(newCollection, -1);
    }

    public synchronized boolean updateConfig(final InstanceConfig newConfig) throws Exception
    {
        ConfigCollection        localConfig = getCollection();
        if ( localConfig.isRolling() )
        {
            return false;
        }

        rollingConfigAdvanceAttempt.set(null);

        ConfigCollection        newCollection = new ConfigCollectionImpl(newConfig, null);
        return internalUpdateConfig(newCollection);
    }

    @VisibleForTesting
    ConfigCollection getCollection()
    {
        return config.get().getConfig();
    }

    @VisibleForTesting
    RollingConfigAdvanceAttempt getRollingConfigAdvanceAttempt()
    {
        return rollingConfigAdvanceAttempt.get();
    }

    private void advanceRollingConfig(ConfigCollection config) throws Exception
    {
        int             rollingHostNamesIndex = config.getRollingConfigState().getRollingHostNamesIndex();
        advanceOrStartRollingConfig(config, rollingHostNamesIndex);
    }

    private boolean advanceOrStartRollingConfig(ConfigCollection config, int rollingHostNamesIndex) throws Exception
    {
        List<String>    rollingHostNames = config.getRollingConfigState().getRollingHostNames();
        boolean         updateConfigResult;
        if ( (rollingHostNamesIndex + 1) >= rollingHostNames.size() )
        {
            // we're done - switch back to single config
            ConfigCollection        newCollection = new ConfigCollectionImpl(config.getRollingConfig(), null);
            updateConfigResult = internalUpdateConfig(newCollection);
        }
        else
        {
            ConfigCollection                newCollection = new ConfigCollectionImpl(config.getRootConfig(), config.getRollingConfig(), rollingHostNames, rollingHostNamesIndex + 1);
            RollingReleaseState             state = new RollingReleaseState(new InstanceState(), newCollection);
            RemoteInstanceRequest.Result    result = checkNextInstanceState(state);
            if ( (result == null) || (result.errorMessage.length() == 0) )
            {
                rollingConfigAdvanceAttempt.set(null);
                updateConfigResult = internalUpdateConfig(newCollection);
            }
            else
            {
                if ( rollingHostNamesIndex >= 0 )
                {
                    ConfigCollection        statusChangeCollection = getBadInstanceStatus(config, state.getCurrentRollingHostname());
                    updateConfigResult = internalUpdateConfig(statusChangeCollection);
                }
                else
                {
                    // this is the start phase - park the bad instance in the back for now
                    List<String>        newRollingHostNames = Lists.newArrayList(rollingHostNames);
                    Collections.rotate(newRollingHostNames, -1);
                    newCollection = new ConfigCollectionImpl(config.getRootConfig(), config.getRollingConfig(), newRollingHostNames, rollingHostNamesIndex + 1);

                    rollingConfigAdvanceAttempt.set(null);
                    updateConfigResult = internalUpdateConfig(newCollection);
                }
            }
        }
        return updateConfigResult;
    }

    private RemoteInstanceRequest.Result checkNextInstanceState(RollingReleaseState state)
    {
        if ( state.getCurrentRollingHostname().equals(exhibitor.getThisJVMHostname()) )
        {
            return null;
        }

        RollingConfigAdvanceAttempt         activeAttempt = rollingConfigAdvanceAttempt.get();

        RemoteInstanceRequest.Result        result;
        if ( (activeAttempt == null) || !activeAttempt.getHostname().equals(state.getCurrentRollingHostname()) || (activeAttempt.getAttemptCount() < MAX_ATTEMPTS) )
        {
            RemoteInstanceRequest           remoteInstanceRequest = new RemoteInstanceRequest(exhibitor, state.getCurrentRollingHostname());
            result = remoteInstanceRequest.makeRequest(exhibitor.getRemoteInstanceRequestClient(), "getStatus");

            if ( activeAttempt == null )
            {
                activeAttempt = new RollingConfigAdvanceAttempt(state.getCurrentRollingHostname());
                rollingConfigAdvanceAttempt.set(activeAttempt);
            }
            activeAttempt.incrementAttemptCount();

            if ( (result.errorMessage.length() != 0) && (activeAttempt.getAttemptCount() >= MAX_ATTEMPTS) )
            {
                result = null;  // it must be down. Skip it.
            }
        }
        else
        {
            result = null;
        }
        return result;
    }

    private boolean internalUpdateConfig(ConfigCollection newCollection) throws Exception
    {
        LoadedInstanceConfig updated = provider.storeConfig(newCollection, config.get().getLastModified());
        if ( updated != null )
        {
            config.set(updated);
            notifyListeners();
            return true;
        }

        return false;
    }

    private synchronized void notifyListeners()
    {
        for ( ConfigListener listener : configListeners )
        {
            listener.configUpdated();
        }
    }

    private synchronized void doWork() throws Exception
    {
        LoadedInstanceConfig    newConfig = provider.loadConfig();
        if ( newConfig.getLastModified() != config.get().getLastModified() )
        {
            config.set(newConfig);
            notifyListeners();
        }
    }

    private ConfigCollection getBadInstanceStatus(final ConfigCollection currentConfig, final String badHostname)
    {
        final RollingConfigState        currentState = currentConfig.getRollingConfigState();
        final RollingConfigState        newState = new RollingConfigState()
        {
            @Override
            public String getRollingStatus()
            {
                return badHostname + " cannot be reached. Will skip if this continues.";
            }

            @Override
            public int getRollingPercentDone()
            {
                return currentState.getRollingPercentDone();
            }

            @Override
            public List<String> getRollingHostNames()
            {
                return currentState.getRollingHostNames();
            }

            @Override
            public int getRollingHostNamesIndex()
            {
                return currentState.getRollingHostNamesIndex();
            }
        };
        return new ConfigCollection()
        {
            @Override
            public InstanceConfig getConfigForThisInstance(String hostname)
            {
                return currentConfig.getConfigForThisInstance(hostname);
            }

            @Override
            public InstanceConfig getRootConfig()
            {
                return currentConfig.getRootConfig();
            }

            @Override
            public InstanceConfig getRollingConfig()
            {
                return currentConfig.getRollingConfig();
            }

            @Override
            public boolean isRolling()
            {
                return currentConfig.isRolling();
            }

            @Override
            public RollingConfigState getRollingConfigState()
            {
                return newState;
            }
        };
    }
}
