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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonValue;
import uk.trainwatch.util.JsonUtils;

/**
 *
 * @author peter
 */
public class Layer
{

    private final String displayName;
    private final String name;
    private final String layerName;
    private final String format;
    private final LocalDateTime defaultDateTime;
    private final String defaultTime;
    private final int timestep[];
    private final List<String> path;

    public Layer( JsonObject o )
    {
        displayName = o.getString( "@displayName" );

        JsonObject service = o.getJsonObject( "Service" );
        name = service.getString( "@name" );
        layerName = service.getString( "LayerName" );
        format = service.getString( "ImageFormat" );

        JsonObject tso = service.getJsonObject( "Timesteps" );

        defaultTime = tso.getString( "@defaultTime" );
        defaultDateTime = JsonUtils.getLocalDateTime( tso, "@defaultTime" );

        timestep = tso.getJsonArray( "Timestep" )
                .stream()
                .filter( v -> v.getValueType() == JsonValue.ValueType.NUMBER )
                .mapToInt( v -> ((JsonNumber) v).intValue() )
                .toArray();

        path = IntStream.of( timestep )
                .mapToObj( ts -> String.join( "/", "/api/modp/wxfcs", layerName, defaultTime, ts + "." + format ) )
                .collect( Collectors.toList() );
    }

    public synchronized IntStream timesteps()
    {
        return IntStream.of( timestep );
    }

    public List<String> getPaths()
    {
        return path;
    }

    public synchronized Stream<String> getUrls( String baseUrl )
    {
        return timesteps()
                .mapToObj( ts -> getUrl( baseUrl, ts ) );
    }

    public synchronized String getUrl( String baseUrl, int ts )
    {
        return baseUrl.replace( "{LayerName}", layerName )
                .replace( "{ImageFormat}", format )
                .replace( "{DefaultTime}", defaultTime )
                .replace( "{Timestep}", String.valueOf( ts ) )
                // Remove key as we add this in DataPoint
                .replaceAll( "\\&key=\\{key\\}", "" );
    }

    @Override
    public int hashCode()
    {
        int hash = 5;
        hash = 37 * hash + Objects.hashCode( this.name );
        return hash;
    }

    @Override
    public boolean equals( Object obj )
    {
        if( this == obj ) {
            return true;
        }
        if( obj == null ) {
            return false;
        }
        if( getClass() != obj.getClass() ) {
            return false;
        }
        final Layer other = (Layer) obj;
        if( !Objects.equals( this.name, other.name ) ) {
            return false;
        }
        return true;
    }

    public String getDisplayName()
    {
        return displayName;
    }

    public String getName()
    {
        return name;
    }

    public String getLayerName()
    {
        return layerName;
    }

    public String getFormat()
    {
        return format;
    }

    public LocalDateTime getDefaultDateTime()
    {
        return defaultDateTime;
    }

    public String getDefaultTime()
    {
        return defaultTime;
    }

    public int[] getTimestep()
    {
        return timestep;
    }

}
