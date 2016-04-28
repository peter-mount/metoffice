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
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonObjectBuilder;
import onl.area51.httpd.HttpRequestHandlerBuilder;
import onl.area51.httpd.action.ActionRegistry;
import onl.area51.httpd.action.Request;
import onl.area51.httpd.rest.JsonEntity;
import onl.area51.httpd.util.PathEntity;
import org.apache.http.HttpEntity;
import uk.trainwatch.util.Functions;
import uk.trainwatch.util.JsonUtils;

/**
 * Handles the /api/modp/layer/wxfcs/* and /api/modp/layer/wxfcs.json endpoints.
 * <p>
 * /api/modp/layer/wxfcs.json will return json defining the current active layers.
 * <p>
 * /api/modp/layer/wxfcs/{LayerName}.json will return the current details about a specific layer and the URL's of the available images.
 * <p>
 * /api/modp/layer/wxfcs/{LayerName}/{DateTime}/{timeStep}.{format} will return the appropriate image.
 *
 * @author peter
 */
@ApplicationScoped
public class ForecastImageLayerWS
{

    private static final String PREFIX = "/api/modp";

    @Inject
    private ForecastImageLayerService forecastImageLayerService;

    public void deploy( @Observes ActionRegistry registry )
    {
        registry.registerHandler( PREFIX + "/layer/wxfcs.json",
                                  HttpRequestHandlerBuilder.create()
                                  .unscoped()
                                  .method( "GET" )
                                  .sendOk( (Supplier) this::sendLayerNames )
                                  .end()
                                  .build() )
                .registerHandler( PREFIX + "/layer/wxfcs/*",
                                  HttpRequestHandlerBuilder.create()
                                  .unscoped()
                                  .method( "GET" )
                                  .add( this::extractPath )
                                  .ifAttributePresentSendOk( "path", PathEntity::create )
                                  .ifAttributePresentSendOk( "layer", this::sendLayer )
                                  .end()
                                  .build() );

    }

    protected void extractPath( Request r )
            throws IOException
    {
        switch( r.getPathLength() ) {
            // ../layer.json
            case 6: {
                String layer = r.getPath( 5 );
                if( layer.endsWith( ".json" ) ) {
                    r.setAttribute( "layer", layer.replace( ".json", "" ) );
                }
            }
            break;

            // ../layer/time/timestep.fmt
            case 8:
                r.setAttribute( "path", forecastImageLayerService.getPath( r.getPath( 5 ), r.getPath( 6 ), r.getPath( 7 ) ) );
                break;

            default:
                break;
        }
    }

    protected HttpEntity sendLayerNames()
    {
        JsonObjectBuilder b = Json.createObjectBuilder()
                .add( "layers", forecastImageLayerService.getLayers()
                      .values()
                      .stream()
                      .reduce( Json.createArrayBuilder(),
                               ( a, l ) -> a.add( l.getLayerName() ),
                               Functions.writeOnceBinaryOperator() ) );
        JsonUtils.add( b, "timestamp", forecastImageLayerService.getLastReload() );
        return new JsonEntity( b );
    }

    protected HttpEntity sendLayer( String layerName, Request request )
    {
        Layer layer = forecastImageLayerService.getLayer( request.getAttribute( layerName ) );
        if( layer != null ) {
            return new JsonEntity( Json.createObjectBuilder()
                    .add( "name", layer.getName() )
                    .add( "layerName", layer.getLayerName() )
                    .add( "displayName", layer.getDisplayName() )
                    .add( "defaultTime", layer.getDefaultTime() )
                    .add( "format", layer.getFormat() )
                    // Array of timesteps
                    .add( "timestep", IntStream.of( layer.getTimestep() )
                          .mapToObj( Integer::valueOf )
                          .reduce( Json.createArrayBuilder(), ( a, i ) -> a.add( i ), Functions.writeOnceBinaryOperator() ) )
                    // Map of timestep to actual image url
                    .add( "images", IntStream.of( layer.getTimestep() )
                          .mapToObj( Integer::valueOf )
                          .reduce( Json.createObjectBuilder(),
                                   ( a, ts ) -> {
                                       Path p = forecastImageLayerService.getPath( layer, ts );
                                       if( p != null ) {
                                           String ps = p.toString();
                                           if( !ps.startsWith( "/" ) ) {
                                               ps = "/" + ps;
                                           }
                                           a.add( String.valueOf( ts ), PREFIX + ps );
                                       }
                                       return a;
                                   },
                                   Functions.writeOnceBinaryOperator() ) )
            );
        }
        return null;
    }
}
