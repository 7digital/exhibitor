package com.netflix.exhibitor.core.state;

import com.netflix.exhibitor.core.Exhibitor;
import com.netflix.exhibitor.core.activity.Activity;
import com.netflix.exhibitor.core.activity.QueueGroups;
import com.netflix.exhibitor.core.activity.RepeatingActivity;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class Checker implements Closeable
{
    private final Exhibitor exhibitor;
    private final AtomicReference<InstanceStateTypes> state = new AtomicReference<InstanceStateTypes>(InstanceStateTypes.LATENT);
    private final RepeatingActivity repeatingActivity;

    public Checker(Exhibitor exhibitor, InstanceStateManager manager)
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
                setState();
                return true;
            }
        };
        repeatingActivity = new RepeatingActivity(exhibitor.getActivityQueue(), QueueGroups.MAIN, activity, exhibitor.getConfig().getCheckMs());
    }

    public void     start()
    {
        repeatingActivity.start();
    }

    @Override
    public void close() throws IOException
    {
        repeatingActivity.close();
    }

    public InstanceStateTypes   getState()
    {
        return state.get();
    }

    private void setState()
    {
        InstanceStateTypes      currentState = state.get();
        InstanceStateTypes      newState = exhibitor.isControlPanelSettingEnabled(ControlPanelTypes.UNLISTED_RESTARTS) ? InstanceStateTypes.UNKNOWN : InstanceStateTypes.DOWN_BECAUSE_UNLISTED;

        String      ruok = new FourLetterWord(FourLetterWord.Word.RUOK, exhibitor.getConfig()).getResponse();
        if ( "imok".equals(ruok) )
        {
            // The following code depends on inside knowledge of the "srvr" response. If they change it
            // this code might break

            List<String> lines = new FourLetterWord(FourLetterWord.Word.SRVR, exhibitor.getConfig()).getResponseLines();
            for ( String line : lines )
            {
                if ( line.contains("not currently serving") )
                {
                    newState = InstanceStateTypes.NOT_SERVING;
                    break;
                }

                if ( line.toLowerCase().startsWith("mode") )
                {
                    newState = InstanceStateTypes.SERVING;
                    break;
                }
            }
        }
        
        if ( newState != currentState )
        {
            state.set(newState);
        }
    }
}
