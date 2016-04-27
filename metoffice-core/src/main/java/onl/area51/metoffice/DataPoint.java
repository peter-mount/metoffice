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
package onl.area51.metoffice;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.isomorphism.util.TokenBucket;
import org.isomorphism.util.TokenBuckets;
import uk.trainwatch.util.MapBuilder;
import uk.trainwatch.util.config.Configuration;
import uk.trainwatch.util.config.ConfigurationService;

/**
 * Provides access to the DataPoint API.
 * <p>
 * Configuration: This requires a JsonObject called "api" within the "metoffice" configuration with the following parameters:
 * <table>
 * <tr><th>Parameter</th><th>Type</th><th>Required</th><th>Purpose</th></tr>
 * <tr><td>apiKey</td><td>String</td><td>Yes</td><td>The DataPoint API key</td></tr>
 * <tr><td>capacity</td><td>Long</td><td>No (50)</td><td>The bucket capacity</td></tr>
 * <tr><td>initialCapacity</td><td>Long</td><td>No (capacity)</td><td>The initial bucket capacity</td></tr>
 * <tr><td>refillTokens</td><td>Long</td><td>No (capacity)</td><td>Tokens to refill for each period</td></tr>
 * <tr><td>period</td><td>Long</td><td>No (1)</td><td>Refill period</td></tr>
 * <tr><td>timeUnit</td><td>TimeUnit</td><td>No (MINUTES)</td><td>TimeUnit for period</td></tr>
 * </table>
 * <p>
 * The token bucket is used to rate limit calls to the MetOffice as they do have limits within the license.
 *
 * @author peter
 */
@ApplicationScoped
public class DataPoint
{

    /**
     * The default capacity and refill amount. Max 50 calls per minute
     */
    private static final int DEFAULT_CAPACITY = 50;

    private static final Logger LOG = Logger.getGlobal();

    @Inject
    private ConfigurationService configurationService;

    private String scheme;
    private String hostname;
    private String path;
    private String apiKey;
    private Level logLevel;
    private TokenBucket bucket;

    private FileSystem fileSystem;

    @PostConstruct
    void start()
    {
        Configuration configuration = configurationService.getConfiguration( "metoffice" );
        Configuration config = configuration.getConfiguration( "api" );

        logLevel = config.get( "log", Level::parse, () -> Level.INFO );

        // These allows us to use an alternate endpoint using the same api. Probably never but it's here
        scheme = config.getString( "scheme", "http" );
        hostname = config.getString( "hostname", "datapoint.metoffice.gov.uk" );
        path = config.getString( "prefix", "/public/data/" );

        // The API key
        apiKey = config.getString( "apiKey" );

        // The rate limiter
        bucket = TokenBuckets.builder()
                .withCapacity( config.getLong( "capacity", DEFAULT_CAPACITY ) )
                .withInitialTokens( config.getLong( "initialCapacity", () -> config.getLong( "capacity", DEFAULT_CAPACITY ) ) )
                .withFixedIntervalRefillStrategy( config.getLong( "refillTokens", DEFAULT_CAPACITY ),
                                                  config.getLong( "period", 1 ),
                                                  config.getEnumOrDefault( "timeUnit", TimeUnit.class, () -> TimeUnit.MINUTES ) )
                .build();

        try {
            fileSystem = FileSystems.newFileSystem( URI.create( "cache://modp" ), configuration.getConfiguration( "cache" ) );
        }
        catch( IOException ex ) {
            LOG.log( Level.SEVERE, null, ex );
            throw new UncheckedIOException( ex );
        }
    }

    @PreDestroy
    void stop()
    {
        if( fileSystem != null ) {
            try {
                fileSystem.close();
            }
            catch( IOException ex ) {
                LOG.log( Level.SEVERE, null, ex );
            }
            finally {
                fileSystem = null;
            }
        }
    }

    public Path getPath( String first, String... more )
    {
        return fileSystem.getPath( first, more );
    }

    /**
     * Call the MetOffice Data Point service
     *
     * @param service  Service to forEach
     * @param function The function in that service
     *
     * @return JsonObject of response
     *
     * @throws java.io.IOException
     * @throws java.net.URISyntaxException
     */
    public JsonObject call( String service, String function )
            throws IOException,
                   URISyntaxException
    {
        return call( service, function, null );
    }

    /**
     * Call the MetOffice Data Point service
     *
     * @param service     Service to forEach
     * @param function    The function in that service
     * @param queryParams Query string of forEach, null for none
     *
     * @return JsonObject of response
     *
     * @throws java.io.IOException
     * @throws java.net.URISyntaxException
     */
    public JsonObject call( String service, String function, Map<String, Object> queryParams )
            throws IOException,
                   URISyntaxException
    {
        // Rate limit ourselves
        bucket.consume();

        URI uri = new URI( scheme, hostname,
                           String.join( "/",
                                        path,
                                        service,
                                        "json",
                                        function ),
                           MapBuilder.<String, Object>builder()
                           .addAll( queryParams )
                           .add( "key", apiKey )
                           .toQueryString(),
                           null );

        try( CloseableHttpClient client = HttpClients.createDefault() ) {
            try( CloseableHttpResponse response = client.execute( new HttpGet( uri ) ) ) {

                int returnCode = response.getStatusLine().getStatusCode();
                LOG.log( Level.FINE, () -> "ReturnCode " + returnCode + ": " + response.getStatusLine().getReasonPhrase() );

                switch( returnCode ) {
                    case 200:
                    case 304:
                        try( InputStream is = response.getEntity().getContent() ) {
                            try( JsonReader r = Json.createReader( is ) ) {
                                JsonObject result = r.readObject();
                                LOG.log( logLevel, () -> String.join( ":", service, function, Objects.toString( result ) ) );
                                return result;
                            }
                        }

                    default:
                        throw new FileNotFoundException();
                }
            }
        }
    }

    public void forEach( String service, String function, Map<String, Object> queryParams, Consumer<JsonObject> c )
            throws IOException,
                   URISyntaxException
    {
        JsonObject o = call( service, function, queryParams );
        if( o != null ) {
            c.accept( o );
        }
    }

    public void forEach( String service, String function, Consumer<JsonObject> c )
            throws IOException,
                   URISyntaxException
    {
        DataPoint.this.forEach( service, function, null, c );
    }

    public void rawCall( String url, Consumer<HttpResponse> c )
            throws IOException,
                   URISyntaxException
    {
        rawCall( url, c, r -> {
         } );
    }

    public void rawCall( String url, Consumer<HttpResponse> success, Consumer<HttpResponse> failure )
            throws IOException,
                   URISyntaxException
    {
        // Rate limit ourselves
        bucket.consume();

        String uri = url.concat( url.contains( "?" ) ? "&key=" + apiKey : "?key=" + apiKey );

        try( CloseableHttpClient client = HttpClients.createDefault() ) {
            try( CloseableHttpResponse response = client.execute( new HttpGet( uri ) ) ) {

                int returnCode = response.getStatusLine().getStatusCode();
                LOG.log( Level.FINE, () -> "ReturnCode " + returnCode + ": " + response.getStatusLine().getReasonPhrase() );

                switch( returnCode ) {
                    case 200:
                    case 304:
                        success.accept( response );
                        break;

                    default:
                        failure.accept( response );
                        break;
                }
            }
        }
    }

}
