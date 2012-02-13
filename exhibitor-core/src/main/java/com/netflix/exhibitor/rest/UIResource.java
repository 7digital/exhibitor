package com.netflix.exhibitor.rest;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import com.netflix.curator.utils.ZKPaths;
import com.netflix.exhibitor.core.InstanceConfig;
import com.netflix.exhibitor.core.activity.ActivityLog;
import com.netflix.exhibitor.core.activity.QueueGroups;
import com.netflix.exhibitor.core.entities.Config;
import com.netflix.exhibitor.core.entities.Result;
import com.netflix.exhibitor.core.entities.SystemState;
import com.netflix.exhibitor.core.entities.UITabSpec;
import com.netflix.exhibitor.core.state.FourLetterWord;
import com.netflix.exhibitor.core.state.KillRunningInstance;
import com.netflix.exhibitor.core.state.ServerList;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.JsonNodeFactory;
import org.codehaus.jackson.node.ObjectNode;
import javax.activation.MimetypesFileTypeMap;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ContextResolver;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Path("exhibitor/v1/ui")
public class UIResource
{
    private final UIContext context;
    private final List<UITab> tabs;

    private static final String         RECURSIVE_FLAG = "*";

    public UIResource(@Context ContextResolver<UIContext> resolver)
    {
        context = resolver.getContext(UIContext.class);
        tabs = buildTabs();
    }

    @Path("{file:.*}")
    @GET
    public Response getResource(@PathParam("file") String fileName) throws IOException
    {
        URL resource;
        try
        {
            resource = Resources.getResource("com/netflix/exhibitor/core/ui/" + fileName);
        }
        catch ( IllegalArgumentException dummy )
        {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        String resourceFile = resource.getFile();
        String contentType;
        if ( resourceFile.endsWith(".png") )
        {
            contentType = "image/png";  // not in default mime types
        }
        else if ( resourceFile.endsWith(".js") )
        {
            contentType = "text/javascript";  // not in default mime types
        }
        else if ( resourceFile.endsWith(".css") )
        {
            contentType = "text/css";  // not in default mime types
        }
        else
        {
            contentType = MimetypesFileTypeMap.getDefaultFileTypeMap().getContentType(resourceFile);
        }
        Object entity;
        if ( contentType.startsWith("text/") )
        {
            entity = Resources.toString(resource, Charset.forName("UTF-8"));
        }
        else
        {
            entity = Resources.toByteArray(resource);
        }
        return Response.ok(entity).type(contentType).build();
    }

    @Path("tabs")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAdditionalTabSpecs()
    {
        final AtomicInteger     index = new AtomicInteger(0);
        List<UITabSpec>         names = Lists.transform
        (
            tabs,
            new Function<UITab, UITabSpec>()
            {
                @Override
                public UITabSpec apply(UITab tab)
                {
                    return new UITabSpec(tab.getName(), "tab/" + index.getAndIncrement());
                }
            }
        );

        GenericEntity<List<UITabSpec>> entity = new GenericEntity<List<UITabSpec>>(names){};
        return Response.ok(entity).build();
    }

    @Path("4ltr/{word}")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response getFourLetterWord(@PathParam("word") String word) throws Exception
    {
        String      value;
        try
        {
            FourLetterWord.Word wordEnum = FourLetterWord.Word.valueOf(word.toUpperCase());
            value = new FourLetterWord(wordEnum, context.getExhibitor().getConfig()).getResponse();
        }
        catch ( IllegalArgumentException e )
        {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(value).build();
    }

    @Path("tab/{index}")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response getAdditionalTabContent(@PathParam("index") int index) throws Exception
    {
        if ( (index < 0) || (index >= tabs.size()) )
        {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(tabs.get(index).getContent()).build();
    }

    @Path("state")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSystemState() throws Exception
    {
        String                      response = new FourLetterWord(FourLetterWord.Word.RUOK, context.getExhibitor().getConfig()).getResponse();
        ServerList serverList = new ServerList(context.getExhibitor().getConfig().getServersSpec());
        ServerList.ServerSpec       us = Iterables.find(serverList.getSpecs(), ServerList.isUs(context.getExhibitor().getConfig().getHostname()), null);
        Config config = new Config
        (
            context.getExhibitor().getConfig().getServersSpec(),
            context.getExhibitor().getConfig().getHostname(),
            (us != null) ? us.getServerId() : -1,
            context.getExhibitor().getConfig().getClientPort(),
            context.getExhibitor().getConfig().getConnectPort(),
            context.getExhibitor().getConfig().getElectionPort(),
            context.getExhibitor().getConfig().getCheckMs(),
            context.getExhibitor().getConfig().getConnectionTimeoutMs(),
            context.getExhibitor().getConfig().getCleanupPeriodMs(),
            context.getExhibitor().getConfig().getZooKeeperInstallDirectory(),
            context.getExhibitor().getConfig().getZooKeeperDataDirectory(),
            context.getExhibitor().getConfig().getCleanupMaxFiles(),
            context.getExhibitor().getConfig().getLogIndexDirectory()
        );
        SystemState state = new SystemState
        (
            config,
            "imok".equals(response),
            context.getExhibitor().restartsAreEnabled(),
            "v0.0.1",       // TODO - correct version
            context.getExhibitor().getCleanupManager().isEnabled()
        );
        return Response.ok(state).build();
    }

    @Path("set/config")
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response setConfig(final Config newConfig) throws Exception
    {
        // TODO - should flush caches as needed

        InstanceConfig wrapped = new InstanceConfig()
        {
            @Override
            public String getLogIndexDirectory()
            {
                return newConfig.getLogIndexDirectory();
            }

            @Override
            public String getZooKeeperInstallDirectory()
            {
                return newConfig.getZooKeeperInstallDir();
            }

            @Override
            public String getZooKeeperDataDirectory()
            {
                return newConfig.getZooKeeperDataDir();
            }

            @Override
            public String getHostname()
            {
                return newConfig.getThisHostname();
            }

            @Override
            public String getServersSpec()
            {
                return newConfig.getServersSpec();
            }

            @Override
            public int getClientPort()
            {
                return newConfig.getClientPort();
            }

            @Override
            public int getConnectPort()
            {
                return newConfig.getConnectPort();
            }

            @Override
            public int getElectionPort()
            {
                return newConfig.getElectionPort();
            }

            @Override
            public int getCheckMs()
            {
                return newConfig.getCheckMs();
            }

            @Override
            public int getConnectionTimeoutMs()
            {
                return newConfig.getConnectionTimeoutMs();
            }

            @Override
            public int getCleanupPeriodMs()
            {
                return newConfig.getCleanupPeriodMs();
            }

            @Override
            public int getCleanupMaxFiles()
            {
                return newConfig.getCleanupMaxFiles();
            }
        };
        context.getExhibitor().updateConfig(wrapped);
        return Response.ok(new Result("OK", true)).build();
    }

    @Path("set/restarts/{value}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response setRestartsState(@PathParam("value") boolean newValue) throws Exception
    {
        context.getExhibitor().setRestartsEnabled(newValue);
        return Response.ok(new Result("OK", true)).build();
    }

    @Path("set/cleanup/{value}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response setCleanupState(@PathParam("value") boolean newValue) throws Exception
    {
        context.getExhibitor().getCleanupManager().setEnable(newValue);
        return Response.ok(new Result("OK", true)).build();
    }

    @Path("stop")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response stopZooKeeper() throws Exception
    {
        context.getExhibitor().getActivityQueue().add
        (
            QueueGroups.MAIN,
            new KillRunningInstance(context.getExhibitor())
            {
                @Override
                public void completed(boolean wasSuccessful)
                {
                }
            }
        );

        return Response.ok(new Result("OK", true)).build();
    }

    private String getLog()
    {
        List<String> log = context.getExhibitor().getLog().toDisplayList("\t");
        StringBuilder str = new StringBuilder();
        for ( String s : log )
        {
            str.append(s).append("\n");
        }

        return str.toString();
    }

    private ImmutableList<UITab> buildTabs()
    {
        ImmutableList.Builder<UITab> builder = ImmutableList.builder();

        builder.add
        (
            new UITab("Log")
            {
                @Override
                public String getContent() throws Exception
                {
                    return getLog();
                }
            }
        );
        Collection<UITab> additionalUITabs = context.getExhibitor().getAdditionalUITabs();
        if ( additionalUITabs != null )
        {
            builder.addAll(additionalUITabs);
        }

        return builder.build();
    }

    private String trimEnding(String path, String ending)
    {
        int     endIndex = path.length() - ending.length();
        if ( endIndex < 1 )
        {
            context.getExhibitor().getLog().add(ActivityLog.Type.INFO, "Ignoring bad path in backups: " + path);
            return null;
        }
        path = path.substring(0, endIndex);
        return path;
    }

}
