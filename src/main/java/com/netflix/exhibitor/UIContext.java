package com.netflix.exhibitor;

import javax.ws.rs.ext.ContextResolver;
import javax.ws.rs.ext.Provider;

@Provider
/**
 * You must inject an instance of this as a Singleton in your REST app.
 */
public class UIContext implements ContextResolver<UIContext>
{
    private final Exhibitor exhibitor;

    /**
     * @param exhibitor the Exhibitor singleton
     */
    public UIContext(Exhibitor exhibitor)
    {
        this.exhibitor = exhibitor;
    }

    public Exhibitor getExhibitor()
    {
        return exhibitor;
    }

    @Override
    public UIContext getContext(Class<?> type)
    {
        return this;
    }
}
