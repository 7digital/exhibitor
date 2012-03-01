package com.netflix.exhibitor.core.rest.jersey;

import com.google.common.collect.Sets;
import com.netflix.exhibitor.core.rest.ClusterResource;
import com.netflix.exhibitor.core.rest.ExplorerResource;
import com.netflix.exhibitor.core.rest.IndexResource;
import com.netflix.exhibitor.core.rest.UIContext;
import com.netflix.exhibitor.core.rest.UIResource;
import com.sun.jersey.api.core.DefaultResourceConfig;
import com.sun.jersey.api.core.ResourceConfig;
import java.util.Set;

@SuppressWarnings("UnusedDeclaration")
public class JerseySupport
{
    public static void      addSingletons(ResourceConfig config, UIContext context)
    {
        config.getSingletons().addAll(getSingletons(context));
    }

    public static void      addClasses(ResourceConfig config)
    {
        config.getClasses().addAll(getClasses());
    }

    /**
     * Return a new Jersey config instance that correctly supplies all needed Exhibitor
     * objects
     *
     * @param context the UIContext
     * @return new config
     */
    public static DefaultResourceConfig newApplicationConfig(UIContext context)
    {
        final Set<Object> singletons = getSingletons(context);
        final Set<Class<?>> classes = getClasses();

        return new DefaultResourceConfig()
        {
            @Override
            public Set<Class<?>> getClasses()
            {
                return classes;
            }

            @Override
            public Set<Object> getSingletons()
            {
                return singletons;
            }
        };
    }

    private static Set<Class<?>> getClasses()
    {
        final Set<Class<?>>     classes = Sets.newHashSet();
        classes.add(UIResource.class);
        classes.add(IndexResource.class);
        classes.add(ExplorerResource.class);
        classes.add(ClusterResource.class);
        return classes;
    }

    private static Set<Object> getSingletons(UIContext context)
    {
        final Set<Object> singletons = Sets.newHashSet();
        singletons.add(context);
        singletons.add(new NaturalNotationContextResolver());
        return singletons;
    }

    private JerseySupport()
    {
    }
}
