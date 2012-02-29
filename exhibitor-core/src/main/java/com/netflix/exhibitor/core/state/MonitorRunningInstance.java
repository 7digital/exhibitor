package com.netflix.exhibitor.core.state;

import com.netflix.exhibitor.core.Exhibitor;
import com.netflix.exhibitor.core.activity.Activity;
import com.netflix.exhibitor.core.activity.ActivityLog;
import com.netflix.exhibitor.core.activity.QueueGroups;
import com.netflix.exhibitor.core.activity.RepeatingActivity;
import com.netflix.exhibitor.core.config.ConfigListener;
import com.netflix.exhibitor.core.config.InstanceConfig;
import com.netflix.exhibitor.core.config.IntConfigs;
import com.netflix.exhibitor.core.config.StringConfigs;
import com.netflix.exhibitor.core.controlpanel.ControlPanelTypes;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

public class MonitorRunningInstance implements Closeable
{
    private final Exhibitor                         exhibitor;
    private final AtomicReference<InstanceState>    currentInstanceState = new AtomicReference<InstanceState>();
    private final RepeatingActivity                 repeatingActivity;

    public MonitorRunningInstance(Exhibitor exhibitor)
    {
        this.exhibitor = exhibitor;
        Activity activity = new Activity()
        {
            @Override
            public void completed(boolean wasSuccessful)
            {
                // NOP
            }

            @Override
            public Boolean call() throws Exception
            {
                doWork();
                return true;
            }
        };

        repeatingActivity = new RepeatingActivity(null, exhibitor.getActivityQueue(), QueueGroups.MAIN, activity, exhibitor.getConfigManager().getConfig().getInt(IntConfigs.CHECK_MS));
    }

    public void start()
    {
        repeatingActivity.start();
        exhibitor.getConfigManager().addConfigListener
        (
            new ConfigListener()
            {
                @Override
                public void configUpdated()
                {
                    repeatingActivity.setTimePeriodMs(exhibitor.getConfigManager().getConfig().getInt(IntConfigs.CHECK_MS));
                }
            }
        );
    }

    @Override
    public void close() throws IOException
    {
        repeatingActivity.close();
    }

    public InstanceStateTypes   getCurrentInstanceState()
    {
        InstanceState   state = currentInstanceState.get();
        return (state != null) ? state.getState() : InstanceStateTypes.LATENT;
    }

    private void doWork() throws Exception
    {
        InstanceConfig  config = exhibitor.getConfigManager().getConfig();

        InstanceState   instanceState = new InstanceState(new ServerList(config.getString(StringConfigs.SERVERS_SPEC)), new Checker(exhibitor).calculateState());
        InstanceState   localCurrentInstanceState = currentInstanceState.get();
        if ( !instanceState.equals(localCurrentInstanceState) )
        {
            boolean         serverListChange = (localCurrentInstanceState != null) && !localCurrentInstanceState.getServerList().equals(instanceState.getServerList());
            currentInstanceState.set(instanceState);

            exhibitor.getLog().add(ActivityLog.Type.INFO, "State: " + instanceState.getState().getDescription());

            if ( serverListChange )
            {
                exhibitor.getLog().add(ActivityLog.Type.INFO, "Server list has changed");
                restartZooKeeper();
            }
            else
            {
                switch ( instanceState.getState() )
                {
                    case DOWN:
                    {
                        restartZooKeeper();
                        break;
                    }

                    default:
                    {
                        // nop
                        break;
                    }
                }
            }
        }
    }

    private void restartZooKeeper() throws Exception
    {
        if ( !exhibitor.getControlPanelValues().isSet(ControlPanelTypes.RESTARTS) )
        {
            return;
        }

        exhibitor.getActivityQueue().add(QueueGroups.MAIN, new KillRunningInstance(exhibitor, true));
    }
}
