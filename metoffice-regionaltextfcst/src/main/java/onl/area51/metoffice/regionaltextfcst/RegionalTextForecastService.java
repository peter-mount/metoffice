/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package onl.area51.metoffice.regionaltextfcst;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonWriter;
import onl.area51.httpd.action.ActionRegistry;
import onl.area51.metoffice.DataPoint;
import uk.trainwatch.scheduler.Cron;
import uk.trainwatch.util.Functions;
import uk.trainwatch.util.JsonUtils;

/**
 *
 * @author peter
 */
@ApplicationScoped
public class RegionalTextForecastService
{

    private static final Logger LOG = Logger.getGlobal();

    @Inject
    private DataPoint dataPoint;

    private LocalDateTime lastReload;

    private Map<Integer, String> locationsById;
    private Map<String, Integer> locationsByName;
    private final Map<Integer, JsonObject> forecasts = new ConcurrentHashMap<>();

    public void deploy( @Observes ActionRegistry registry )
    {
        // Do nothing, just ensure we start when the web server does
    }

    public JsonObject getForecast( int id )
    {
        return forecasts.get( id );
    }

    public JsonObject getForecast( String name )
    {
        Integer id = locationsByName.get( name );
        return id == null ? null : forecasts.get( id );
    }

    public Collection<String> getNames()
    {
        return locationsById.values();
    }

    public Collection<Integer> getIDs()
    {
        return locationsByName.values();
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
                Logger.getLogger( RegionalTextForecastService.class.getName() ).log( Level.SEVERE, null, ex );
            }
        } ).start();
    }

    @Cron("0 5/5 4-8,16-20 * * ? *")
    public void reload()
            throws IOException,
                   URISyntaxException
    {
        LocalDateTime now = LocalDateTime.now();
        if( lastReload == null || Duration.between( lastReload, now ).getSeconds() > 21600 ) {

            JsonObject obj = dataPoint.call( "txt/wxfcs/regionalforecast", "capabilities" ).getJsonObject( "RegionalFcst" );
            LocalDateTime issuedAt = JsonUtils.getLocalDateTime( obj, "issuedAt" );

            if( locationsById == null ) {
                locationsById = dataPoint.call( "txt/wxfcs/regionalforecast", "sitelist" )
                        .getJsonObject( "Locations" )
                        .getJsonArray( "Location" )
                        .stream()
                        .map( Functions.castTo( JsonObject.class ) )
                        .filter( Objects::nonNull )
                        .collect( Collectors.toConcurrentMap( o -> Integer.parseInt( o.getString( "@id" ) ),
                                                              o -> o.getString( "@name" ) ) );

                locationsByName = locationsById.entrySet()
                        .stream()
                        .collect( Collectors.toConcurrentMap( Map.Entry::getValue, Map.Entry::getKey ) );
            }

            for( Map.Entry<Integer, String> e: locationsById.entrySet() ) {
                try {
                    Path path = dataPoint.getPath( "txt/wxfcs/regionalforecast", issuedAt.toString(), e.getValue() + ".json" );

                    if( Files.exists( path, LinkOption.NOFOLLOW_LINKS ) ) {
                        // Read from the cache
                        try( JsonReader r = Json.createReader( Files.newBufferedReader( path ) ) ) {
                            forecasts.put( e.getKey(), r.readObject() );
                        }
                    }
                    else {
                        // Retrieve the new forecast and store in the cache
                        obj = dataPoint.call( "txt/wxfcs/regionalforecast", e.getKey().toString() );

                        // Store the result
                        try( OutputStream os = Files.newOutputStream( path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.WRITE ) ) {
                            try( JsonWriter w = Json.createWriter( os ) ) {
                                w.writeObject( obj );
                            }
                        }

                        forecasts.put( e.getKey(), obj );
                    }
                }
                catch( IOException ex ) {
                    LOG.log( Level.SEVERE, "Failed to get " + issuedAt + " " + e.getValue(), ex );
                }
            }

            lastReload = now;
        }
    }

}
