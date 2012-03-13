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

import com.google.common.collect.ImmutableList;
import java.util.List;

class ConfigCollectionImpl implements ConfigCollection
{
    private final InstanceConfig rootConfig;
    private final InstanceConfig rollingConfig;
    private final String rollingStatus;
    private ImmutableList<String> rollingHostNames;

    ConfigCollectionImpl(InstanceConfig rootConfig, InstanceConfig rollingConfig)
    {
        this(rootConfig, rollingConfig, ImmutableList.<String>of());
    }
    
    ConfigCollectionImpl(InstanceConfig rootConfig, InstanceConfig rollingConfig, List<String> rollingHostNames)
    {
        this.rootConfig = rootConfig;
        this.rollingConfig = rollingConfig;
        this.rollingHostNames = ImmutableList.copyOf(rollingHostNames);

        String      rollingStatus = "n/a";
        if ( rollingHostNames.size() > 0 )
        {
            rollingStatus = "Applying to " + rollingHostNames.get(rollingHostNames.size() - 1);
        }
        this.rollingStatus = rollingStatus;
    }

    @Override
    public String getRollingStatus()
    {
        return rollingStatus;
    }

    @Override
    public InstanceConfig getConfigForThisInstance(String hostname)
    {
        return getRollingConfig();
    }

    @Override
    public InstanceConfig getRootConfig()
    {
        return rootConfig;
    }

    @Override
    public InstanceConfig getRollingConfig()
    {
        return (rollingConfig != null) ? rollingConfig : rootConfig;
    }

    @Override
    public boolean isRolling()
    {
        return (rollingConfig != null);
    }

    @Override
    public List<String> getRollingHostNames()
    {
        return rollingHostNames;
    }
}
