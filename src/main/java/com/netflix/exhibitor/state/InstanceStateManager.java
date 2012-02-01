package com.netflix.exhibitor.state;

import com.google.common.collect.Iterables;
import com.netflix.exhibitor.Exhibitor;
import java.io.Closeable;
import java.io.IOException;

public class InstanceStateManager implements Closeable
{
    private final Exhibitor         exhibitor;
    private final Checker           checker;

    public InstanceStateManager(Exhibitor exhibitor)
    {
        this.exhibitor = exhibitor;
        checker = new Checker(exhibitor, this);
    }

    public void start()
    {
        checker.start();
    }

    @Override
    public void close() throws IOException
    {
        checker.close();
    }

    public InstanceState getInstanceState()
    {
        ServerList              serverList = new ServerList(exhibitor.getConfig().getServerSpec());
        ServerList.ServerSpec   us = Iterables.find(serverList.getSpecs(), ServerList.isUs(exhibitor.getConfig().getHostname()), null);
        InstanceStateTypes      state = (us != null) ? checker.getState() : InstanceStateTypes.WAITING;
        return new InstanceState
        (
            serverList,
            exhibitor.getConfig().getConnectPort(),
            exhibitor.getConfig().getElectionPort(),
            (us != null) ? us.getServerId() : -1,
            state,
            checker.getState()
        );
    }
}
