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

/**
 * Config selectors for integer values
 */
public enum IntConfigs
{
    /**
     * The port to connect to the ZK server - default: 2181
     */
    CLIENT_PORT()
    {
        @Override
        public boolean isRestartSignificant()
        {
            return true;
        }
    },

    /**
     * The port ZK instances use to connect to each other - default: 2888
     */
    CONNECT_PORT()
    {
        @Override
        public boolean isRestartSignificant()
        {
            return true;
        }
    },

    /**
     * The 2nd port ZK instances use to connect to each other - default: 3888
     */
    ELECTION_PORT()
    {
        @Override
        public boolean isRestartSignificant()
        {
            return true;
        }
    },

    /**
     * Period in ms to check that ZK is running - default: 30000
     */
    CHECK_MS()
    {
        @Override
        public boolean isRestartSignificant()
        {
            return false;
        }
    },

    /**
     * Period in ms to perform log cleanup - default: 12 hours
     */
    CLEANUP_PERIOD_MS()
    {
        @Override
        public boolean isRestartSignificant()
        {
            return false;
        }
    },

    /**
     * Value to pass to PurgeTxnLog as max - default: 3
     */
    CLEANUP_MAX_FILES()
    {
        @Override
        public boolean isRestartSignificant()
        {
            return false;
        }
    },

    /**
     * Max backup session to retain - default: 5
     */
    BACKUP_MAX_STORE_MS()
    {
        @Override
        public boolean isRestartSignificant()
        {
            return false;
        }
    },

    /**
     * Period in ms to perform backups - default: 60000
     */
    BACKUP_PERIOD_MS()
    {
        @Override
        public boolean isRestartSignificant()
        {
            return false;
        }
    },
    ;

    /**
     * Return true if a change to this config requires that the ZK instance be restarted
     *
     * @return true/false
     */
    public abstract boolean     isRestartSignificant();
}
