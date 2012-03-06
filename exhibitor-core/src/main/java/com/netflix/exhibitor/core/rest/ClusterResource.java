package com.netflix.exhibitor.core.rest;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.netflix.exhibitor.core.activity.QueueGroups;
import com.netflix.exhibitor.core.config.InstanceConfig;
import com.netflix.exhibitor.core.controlpanel.ControlPanelTypes;
import com.netflix.exhibitor.core.entities.Result;
import com.netflix.exhibitor.core.state.FourLetterWord;
import com.netflix.exhibitor.core.state.InstanceStateTypes;
import com.netflix.exhibitor.core.state.KillRunningInstance;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.WebResource;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ObjectNode;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.PathSegment;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.ContextResolver;
import java.net.URI;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * REST calls for dealing with indexed (via Lucene) log files
 */
@SuppressWarnings("UnusedDeclaration")
@Path("exhibitor/v1/ui/cluster")
public class ClusterResource
{
    private final UIContext context;
    private final Client client;
    private final LoadingCache<URI, WebResource> webResources = CacheBuilder.newBuilder()
        .softValues()
        .build
        (
            new CacheLoader<URI, WebResource>()
            {
                @Override
                public WebResource load(URI remoteUri) throws Exception
                {
                    return client.resource(remoteUri);
                }
            }
        );

    public ClusterResource(@Context ContextResolver<UIContext> resolver)
    {
        context = resolver.getContext(UIContext.class);
        client = Client.create();
    }

    @Path("state/{hostname}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String   remoteGetStatus(@Context UriInfo uriInfo, @PathParam("hostname") String hostname) throws Exception
    {
        return makeRemoteRequest
        (
            uriInfo,
            hostname,
            new Callable<String>()
            {
                @Override
                public String call() throws Exception
                {
                    return getStatus();
                }
            }
        );
    }

    @Path("set/{type}/{value}/{hostname}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String remoteSetControlPanelSetting(@Context UriInfo uriInfo, @PathParam("hostname") String hostname, final @PathParam("type") String typeStr, final @PathParam("value") boolean newValue) throws Exception
    {
        return makeRemoteRequest
        (
            uriInfo, 
            hostname,
            new Callable<String>()
            {
                @Override
                public String call() throws Exception
                {
                    return setControlPanelSetting(typeStr, newValue);
                }
            }
        );
    }

    @Path("restart/{hostname}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String remoteStopStartZooKeeper(@Context UriInfo uriInfo, @PathParam("hostname") String hostname) throws Exception
    {
        return makeRemoteRequest
        (
            uriInfo,
            hostname,
            new Callable<String>()
            {
                @Override
                public String call() throws Exception
                {
                    return stopStartZooKeeper();
                }
            }
        );
    }

    @Path("4ltr/{word}/{hostname}")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String remoteGetFourLetterWord(@Context UriInfo uriInfo, @PathParam("hostname") String hostname, @PathParam("word") final String word) throws Exception
    {
        return makeRemoteRequest
        (
            uriInfo,
            hostname,
            false,
            new Callable<String>()
            {
                @Override
                public String call() throws Exception
                {
                    return getFourLetterWord(word);
                }
            }
        );
    }

    @Path("log/{hostname}")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String remoteGetLog(@Context UriInfo uriInfo, @PathParam("hostname") String hostname) throws Exception
    {
        return makeRemoteRequest
        (
            uriInfo,
            hostname,
            false,
            new Callable<String>()
            {
                @Override
                public String call() throws Exception
                {
                    return getLog();
                }
            }
        );
    }

    @Path("log")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getLog() throws Exception
    {
        return UIResource.getLog(context);
    }

    @Path("4ltr/{word}")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getFourLetterWord(@PathParam("word") String word) throws Exception
    {
        InstanceConfig config = context.getExhibitor().getConfigManager().getConfig();

        String      value;
        try
        {
            FourLetterWord.Word wordEnum = FourLetterWord.Word.valueOf(word.toUpperCase());
            value = new FourLetterWord(wordEnum, config, context.getExhibitor().getConnectionTimeOutMs()).getResponse();
        }
        catch ( IllegalArgumentException e )
        {
            return "* unknown *";
        }
        return value;
    }

    @Path("restart")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String stopStartZooKeeper() throws Exception
    {
        context.getExhibitor().getActivityQueue().add(QueueGroups.MAIN, new KillRunningInstance(context.getExhibitor(), true));

        Result result = new Result("OK", true);
        return new ObjectMapper().writer().writeValueAsString(result);
    }

    @Path("set/{type}/{value}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String setControlPanelSetting(@PathParam("type") String typeStr, @PathParam("value") boolean newValue) throws Exception
    {

        ControlPanelTypes   type = null;
        try
        {
            type = ControlPanelTypes.fuzzyFind(typeStr);
        }
        catch ( IllegalArgumentException ignore )
        {
            // ignore
        }
        
        Result      result;
        if ( type != null )
        {
            context.getExhibitor().getControlPanelValues().set(type, newValue);
            result = new Result("OK", true);
        }
        else
        {
            result = new Result("Not found", false);
        }
        return new ObjectMapper().writer().writeValueAsString(result);
    }

    @Path("state")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String   getStatus() throws Exception
    {
        ObjectMapper        mapper = new ObjectMapper();
        ObjectNode          mainNode = mapper.getNodeFactory().objectNode();

        ObjectNode          switchesNode = mapper.getNodeFactory().objectNode();
        for ( ControlPanelTypes type : ControlPanelTypes.values() )
        {
            switchesNode.put(UIResource.fixName(type), context.getExhibitor().getControlPanelValues().isSet(type));
        }
        mainNode.put("switches", switchesNode);

        InstanceStateTypes      state = context.getExhibitor().getMonitorRunningInstance().getCurrentInstanceState();
        mainNode.put("state", state.getCode());
        mainNode.put("description", state.getDescription());

        return mapper.writer().writeValueAsString(mainNode);
    }

    private String    makeRemoteRequest(UriInfo uriInfo, String hostname, Callable<String> proc) throws Exception
    {
        return makeRemoteRequest(uriInfo, hostname, true, proc);
    }

    private String    makeRemoteRequest(UriInfo uriInfo, String hostname, boolean responseIsJson, Callable<String> proc) throws Exception
    {
        String      remoteResponse;
        String      errorMessage;
        if ( hostname.equals("localhost") || hostname.equals(context.getExhibitor().getThisJVMHostname()) )
        {
            remoteResponse = proc.call();
            errorMessage = "";
        }
        else
        {
            try
            {
                String      thisPath = uriInfo.getPath();
                if ( !thisPath.endsWith(hostname) )
                {
                    throw new IllegalStateException("Unknown path format: " + thisPath);
                }
                String      remotePath = thisPath.substring(0, thisPath.length() - hostname.length());
                UriBuilder  builder = uriInfo.getRequestUriBuilder();
                List<PathSegment> segments = uriInfo.getPathSegments();
                URI         remoteUri = builder.replacePath(remotePath).host(hostname).build();

                WebResource         resource = webResources.get(remoteUri);
                remoteResponse = resource.accept(MediaType.APPLICATION_JSON_TYPE).get(String.class);
                errorMessage = "";
            }
            catch ( Exception e )
            {
                remoteResponse = "{}";
                errorMessage = e.getMessage();
                if ( errorMessage == null )
                {
                    errorMessage = "Unknown";
                }
            }
        }

        ObjectMapper        mapper = new ObjectMapper();
        ObjectNode          node = mapper.getNodeFactory().objectNode();
        if ( responseIsJson )
        {
            node.put("response", mapper.readTree(remoteResponse));
        }
        else
        {
            node.put("response", remoteResponse);
        }
        node.put("errorMessage", errorMessage);
        node.put("success", errorMessage.length() == 0);

        return mapper.writer().writeValueAsString(node);
    }
}
