package com.netflix.exhibitor.core.config;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * The default values
 */
public class DefaultProperties
{
    public static int asInt(String s)
    {
        try
        {
            return Integer.parseInt(s);
        }
        catch ( NumberFormatException e )
        {
            // ignore
        }
        return 0;
    }

    public static Properties get()
    {
        InstanceConfig                  source = new InstanceConfig()
        {
            @Override
            public String getString(StringConfigs config)
            {
                return "";
            }

            @Override
            public int getInt(IntConfigs config)
            {
                switch ( config )
                {
                    case CLIENT_PORT:
                    {
                        return 2181;
                    }

                    case CONNECT_PORT:
                    {
                        return 2888;
                    }

                    case ELECTION_PORT:
                    {
                        return 3888;
                    }

                    case CHECK_MS:
                    {
                        return (int)TimeUnit.MILLISECONDS.convert(30, TimeUnit.SECONDS);
                    }

                    case CLEANUP_PERIOD_MS:
                    {
                        return (int)TimeUnit.MILLISECONDS.convert(12, TimeUnit.HOURS);
                    }

                    case CLEANUP_MAX_FILES:
                    {
                        return 3;
                    }

                    case BACKUP_PERIOD_MS:
                    {
                        return (int)TimeUnit.MILLISECONDS.convert(1, TimeUnit.MINUTES);
                    }

                    case BACKUP_MAX_FILES:
                    {
                        return 5;
                    }
                }
                return 0;
            }
        };
        PropertyBasedInstanceConfig     config = new PropertyBasedInstanceConfig(source);
        return config.getProperties();
    }

    private DefaultProperties()
    {
    }
}
