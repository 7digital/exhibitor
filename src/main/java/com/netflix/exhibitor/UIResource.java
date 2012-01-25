package com.netflix.exhibitor;

import com.google.common.base.Function;
import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import com.netflix.curator.utils.ZKPaths;
import com.netflix.exhibitor.activity.ActivityLog;
import com.netflix.exhibitor.activity.QueueGroups;
import com.netflix.exhibitor.entities.BackupPojo;
import com.netflix.exhibitor.entities.ConfigPojo;
import com.netflix.exhibitor.entities.PathPojo;
import com.netflix.exhibitor.entities.ResultPojo;
import com.netflix.exhibitor.entities.ServerPojo;
import com.netflix.exhibitor.entities.SystemState;
import com.netflix.exhibitor.entities.UITabSpec;
import com.netflix.exhibitor.spi.BackupPath;
import com.netflix.exhibitor.spi.BackupSpec;
import com.netflix.exhibitor.spi.ServerInfo;
import com.netflix.exhibitor.spi.UITab;
import com.netflix.exhibitor.state.FourLetterWord;
import com.netflix.exhibitor.state.InstanceStateManager;
import com.netflix.exhibitor.state.KillRunningInstance;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.JsonNodeFactory;
import org.codehaus.jackson.node.ObjectNode;
import javax.activation.MimetypesFileTypeMap;
import javax.ws.rs.Consumes;
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
import java.text.DateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

@Path("exhibitor/v1/ui")
public class UIResource
{
    private final UIContext context;
    private final List<UITab> tabs;
    private final DateFormat formatter;

    private static final String         ERROR_KEY = "*";
    private static final String         RECURSIVE_FLAG = "*";

    private static final Function<PathPojo,String> toPojo = new Function<PathPojo, String>()
    {
        @Override
        public String apply(PathPojo pojo)
        {
            return pojo.getPath();
        }
    };

    public UIResource(@Context ContextResolver<UIContext> resolver)
    {
        context = resolver.getContext(UIContext.class);
        tabs = buildTabs();

        formatter = DateFormat.getDateTimeInstance();
        formatter.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    @Path("{file:.*}")
    @GET
    public Response getResource(@PathParam("file") String fileName) throws IOException
    {
        URL resource;
        try
        {
            resource = Resources.getResource("com/netflix/exhibitor/ui/" + fileName);
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
        Collection<ServerInfo>      servers = context.getExhibitor().getGlobalSharedConfig().getServers();
        if ( servers == null )
        {
            servers = Lists.newArrayList();
        }
        ServerInfo                  us = Iterables.find(servers, InstanceStateManager.isUs, null);
        Collection<ServerPojo>      localServers = Collections2.transform
        (
            servers,
            new Function<ServerInfo, ServerPojo>()
            {
                @Override
                public ServerPojo apply(ServerInfo info)
                {
                    return new ServerPojo(info.getHostname(), info.getId());
                }
            }
        );

        Collection<BackupSpec>      backupsFromConfig = context.getExhibitor().getBackupManager().getSource().getAvailableBackups();
        List<BackupSpec>            availableBackups = (backupsFromConfig != null) ? Lists.newArrayList(backupsFromConfig) : Lists.<BackupSpec>newArrayList();
        Collections.sort
        (
            availableBackups,
            new Comparator<BackupSpec>()
            {
                @Override
                public int compare(BackupSpec o1, BackupSpec o2)
                {
                    return o2.getDate().compareTo(o1.getDate());    // descending
                }
            }
        );
        
        Collection<BackupPojo>     localAvailableRestores = Collections2.transform
        (
            availableBackups,
            new Function<BackupSpec, BackupPojo>()
            {
                @Override
                public BackupPojo apply(BackupSpec spec)
                {
                    return new BackupPojo(spec.getPath(), formatter.format(spec.getDate()));
                }
            }
        );

        Collection<BackupPath>      backupPaths = context.getExhibitor().getGlobalSharedConfig().getBackupPaths();
        Collection<String>          localBackupPaths = Collections2.transform
        (
            (backupPaths != null) ? backupPaths : Lists.<BackupPath>newArrayList(),
            new Function<BackupPath, String>()
            {
                @Override
                public String apply(BackupPath backupPath)
                {
                    return backupPath.isRecursive() ? ZKPaths.makePath(backupPath.getPath(), RECURSIVE_FLAG) : backupPath.getPath();
                }
            }
        );

        String                      response = new FourLetterWord(FourLetterWord.Word.RUOK, context.getExhibitor().getConfig()).getResponse();
        ConfigPojo                  config = new ConfigPojo
        (
            localServers,
            (us != null) ? us.getId() : -1,
            context.getExhibitor().getConfig().getHostname(),
            localBackupPaths,
            localAvailableRestores
        );
        SystemState state = new SystemState
        (
            config,
            "imok".equals(response),
            context.getExhibitor().restartsAreEnabled(),
            "v0.0.1",       // TODO - correct version
            context.getExhibitor().backupCleanupEnabled()
        );
        return Response.ok(state).build();
    }

    @Path("set/backup-cleanups/{value}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response setBackupCleanupState(@PathParam("value") boolean newValue) throws Exception
    {
        context.getExhibitor().setBackupCleanupEnabled(newValue);
        return Response.ok(new ResultPojo("OK", true)).build();
    }

    @Path("set/restarts/{value}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response setRestartsState(@PathParam("value") boolean newValue) throws Exception
    {
        context.getExhibitor().setRestartsEnabled(newValue);
        return Response.ok(new ResultPojo("OK", true)).build();
    }

    @Path("set/servers")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setServers(List<PathPojo> paths) throws Exception
    {
        Collection<String>      localPaths = Collections2.transform(paths, toPojo);
        Collection<ServerInfo>  servers = Collections2.transform
        (
            localPaths,
            new Function<String, ServerInfo>()
            {
                @Override
                public ServerInfo apply(String str)
                {
                    String[] parts = str.trim().split(":");
                    if ( parts.length == 2 )
                    {
                        String hostname = parts[1];
                        try
                        {
                            return new ServerInfo(hostname, Integer.parseInt(parts[0]), hostname.equals(context.getExhibitor().getConfig().getHostname()));
                        }
                        catch ( NumberFormatException ignore )
                        {
                            // ignore
                        }
                    }
                    return null;
                }
            }
        );
        Collection<ServerInfo>  filtered = Collections2.filter(servers, Predicates.notNull());
        
        context.getExhibitor().getGlobalSharedConfig().setServers(filtered);
        return Response.ok(new ResultPojo("OK", true)).build();
    }

    @Path("set/backup-paths")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setBackupPaths(List<PathPojo> paths) throws Exception
    {
        Collection<BackupPath>      localPaths = Collections2.transform
        (
            paths,
            new Function<PathPojo, BackupPath>()
            {
                @Override
                public BackupPath apply(PathPojo pojo)
                {
                    String      path = pojo.getPath();
                    String      originalPath = path;
                    boolean     isRecursive = path.endsWith(RECURSIVE_FLAG);
                    if ( isRecursive )
                    {
                        path = trimEnding(path, RECURSIVE_FLAG);
                        if ( path == null )
                        {
                            return null;
                        }
                    }
                    if ( path.endsWith("/") )
                    {
                        path = trimEnding(path, "/");
                        if ( path == null )
                        {
                            return null;
                        }
                    }
                    try
                    {
                        return new BackupPath(path, isRecursive);
                    }
                    catch ( IllegalArgumentException ignore )
                    {
                        // ignore
                    }
                    return null;
                }
            }
        );
        localPaths = Collections2.filter(localPaths, Predicates.notNull());

        context.getExhibitor().getGlobalSharedConfig().setBackupPaths(localPaths);
        return Response.ok(new ResultPojo("OK", true)).build();
    }

    @Path("start-backup")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response startBackup() throws Exception
    {
        context.getExhibitor().getBackupManager().forceBackup();
        return Response.ok(new ResultPojo("OK", true)).build();
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

        return Response.ok(new ResultPojo("OK", true)).build();
    }

    @GET
    @Path("node-data")
    @Produces("application/json")
    public String   getNodeData(@QueryParam("key") String key) throws Exception
    {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        try
        {
            Stat            stat = context.getExhibitor().getLocalConnection().checkExists().forPath(key);
            byte[]          bytes = context.getExhibitor().getLocalConnection().getData().storingStatIn(stat).forPath(key);

            StringBuilder       bytesStr = new StringBuilder();
            for ( byte b : bytes )
            {
                bytesStr.append(Integer.toHexString(b & 0xff)).append(" ");
            }

            node.put("bytes", bytesStr.toString());
            node.put("str", new String(bytes, "UTF-8"));
            node.put("stat", reflectToString(stat));
        }
        catch ( KeeperException.NoNodeException dummy )
        {
            node.put("bytes", "");
            node.put("str", "");
            node.put("stat", "* not found * ");
        }
        catch ( Throwable e )
        {
            node.put("bytes", "");
            node.put("str", "Exception");
            node.put("stat", e.getMessage());
        }
        return node.toString();
    }

    @GET
    @Path("node")
    @Produces("application/json")
    public String   getNode(@QueryParam("key") String key) throws Exception
    {
        ArrayNode children = JsonNodeFactory.instance.arrayNode();
        try
        {
            List<String> childrenNames = context.getExhibitor().getLocalConnection().getChildren().forPath(key);
            Collections.sort(childrenNames);
            for ( String name : childrenNames )
            {
                ObjectNode  node = children.addObject();
                node.put("title", name);
                node.put("key", ZKPaths.makePath(key, name));
                node.put("isLazy", true);
                node.put("expand", false);
            }
        }
        catch ( Throwable e )
        {
            context.getExhibitor().getLog().add(ActivityLog.Type.ERROR, "getNode: " + key, e);

            ObjectNode  node = children.addObject();
            node.put("title", "* Exception *");
            node.put("key", ERROR_KEY);
            node.put("isLazy", false);
            node.put("expand", false);
        }

        return children.toString();
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

    private String getZKStats()
    {
        StringBuilder       str = new StringBuilder();
        for ( FourLetterWord.Word word : FourLetterWord.Word.values() )
        {
            String  value = new FourLetterWord(word, context.getExhibitor().getConfig()).getResponse();
            str.append(word.name()).append("\n");
            str.append("====").append("\n");
            str.append(value).append("\n");
            str.append("________________________________________________________________________________").append("\n\n");
        }

        return str.toString();
    }

    private ImmutableList<UITab> buildTabs()
    {
        ImmutableList.Builder<UITab> builder = ImmutableList.builder();

        builder.add
        (
            new UITab()
            {
                @Override
                public String getName()
                {
                    return "Log";
                }

                @Override
                public String getContent() throws Exception
                {
                    return getLog();
                }
            },

            new UITab()
            {
                @Override
                public String getName()
                {
                    return "4LTR";
                }

                @Override
                public String getContent() throws Exception
                {
                    return getZKStats();
                }
            }
        );
        Collection<UITab> additionalUITabs = context.getExhibitor().getConfig().getAdditionalUITabs();
        if ( additionalUITabs != null )
        {
            builder.addAll(additionalUITabs);
        }

        return builder.build();
    }

    private String  reflectToString(Object obj) throws Exception
    {
        StringBuilder       str = new StringBuilder();
        for ( Field f : obj.getClass().getDeclaredFields() )
        {
            f.setAccessible(true);

            if ( str.length() > 0 )
            {
                str.append(", ");
            }
            str.append(f.getName()).append(": ");
            str.append(f.get(obj));
        }
        return str.toString();
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
