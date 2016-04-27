/*
 * Copyright 2016 peter.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package onl.area51.metoffice.metoffice.forecast.layer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.json.JsonObject;
import onl.area51.httpd.action.ActionRegistry;
import onl.area51.metoffice.DataPoint;
import uk.trainwatch.scheduler.Cron;
import uk.trainwatch.util.Functions;

/**
 *
 * @author peter
 */
@ApplicationScoped
public class ForecastImageLayerService
{

    private static final Logger LOG = Logger.getGlobal();

    private static final String PREFIX = "layer/wxfcs";
    private static final String SERVICE_ALL = PREFIX + "/all";

    @Inject
    private DataPoint dataPoint;

    /**
     * Event used to notify that a layer has been updated
     */
    @Inject
    private Event<Layer> layerEvent;

    /**
     * Event used to notify that a path has been updated
     */
    @Inject
    private Event<Path> pathEvent;

    private String baseUrl;
    private Map<String, Layer> layers;

    private LocalDateTime lastReload;

    public synchronized String getBaseUrl()
    {
        return baseUrl;
    }

    public synchronized Map<String, Layer> getLayers()
    {
        return layers;
    }

    public Layer getLayer( String name )
    {
        return getLayers().get( name );
    }

    public void deploy( @Observes ActionRegistry registry )
    {
        // Do nothing, just ensure we start when the web server does
    }

    @PostConstruct
    void start()
    {
        new Thread( () -> {
            try {
                reload();
            }
            catch( IOException |
                   URISyntaxException ex ) {
                LOG.log( Level.SEVERE, null, ex );
            }
        } ).start();
    }

    @Cron("0 5/5 0/3 * * ? *")
    public void reload()
            throws IOException,
                   URISyntaxException
    {
        LocalDateTime now = LocalDateTime.now();

        // Only reload if our last reload was at least 2 hours ago
        if( lastReload == null || Duration.between( lastReload, now ).getSeconds() > 3600 ) {
            dataPoint.forEach( SERVICE_ALL, "capabilities", cap -> {
                           // Reload our config
                           reloadLayers( cap );

                           // OUTSIDE the synchronized block load those layers then notify anyone of the update
                           getLayers().values().forEach( this::retrieveLayer );

                           lastReload = now;
                       }
            );
        }
    }

    private synchronized void reloadLayers( JsonObject cap )
    {
        JsonObject obj = cap.getJsonObject( "Layers" );

        baseUrl = obj.getJsonObject( "BaseUrl" ).getString( "$" );

        layers = obj.getJsonArray( "Layer" )
                .stream()
                .map( Functions.castTo( JsonObject.class ) )
                .filter( Objects::nonNull )
                .map( Layer::new )
                .collect( Collectors.toMap( Layer::getLayerName, Function.identity() ) );
    }

    public LocalDateTime getLastReload()
    {
        return lastReload;
    }

    public Path getPath( Layer layer, int timestep )
    {
        return getPath( layer, layer.getDefaultTime(), timestep + "." + layer.getFormat() );
    }

    public Path getPath( String layerName, String time, String img )
    {
        return getPath( getLayer( layerName ), time, img );
    }

    public Path getPath( Layer layer, String time, String img )
    {
        if( layer == null ) {
            return null;
        }
        return dataPoint.getPath( PREFIX, layer.getLayerName(), time, img );
    }

    private void retrieveLayer( Layer layer )
    {
        layer.timesteps()
                .forEach( timestep -> {

                    String url = layer.getUrl( baseUrl, timestep );

                    Path path = getPath( layer, timestep );
                    if( path != null ) {
                        try {
                            dataPoint.rawCall( url,
                                               r -> {
                                                   try {
                                                       Files.copy( r.getEntity().getContent(), path, StandardCopyOption.REPLACE_EXISTING );
                                                   }
                                                   catch( IOException ex ) {
                                                       LOG.log( Level.SEVERE, ex, () -> "Failed to persist " + path );
                                                   }

                                                   pathEvent.fire( path );
                                               },
                                               r -> LOG.log( Level.SEVERE,
                                                             () -> r.getStatusLine().getStatusCode() + ":" + r.getStatusLine().getReasonPhrase()
                                                                   + " " + url )
                            );
                        }
                        catch( IOException |
                               UncheckedIOException |
                               URISyntaxException ex ) {
                            LOG.log( Level.SEVERE, ex, () -> "Failed to retrieve " + url );
                        }
                    }
                } );

        layerEvent.fire( layer );
    }

}
