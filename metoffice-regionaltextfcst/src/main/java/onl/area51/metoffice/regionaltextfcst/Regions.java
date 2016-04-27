/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package onl.area51.metoffice.regionaltextfcst;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import uk.trainwatch.util.MapBuilder;

/**
 *
 * @author peter
 */
public enum Regions
{
    OS( "os", "Orkney & Shetland" ),
    HE( "he", "Highland & Eilean Siar" ),
    GR( "gr", "Grampian" ),
    TA( "ta", "Tayside" ),
    ST( "st", "Strathclyde" ),
    DG( "dg", "Dumfries, Galloway, Lothian" ),
    NI( "ni", "Northern Ireland" ),
    YH( "yh", "Yorkshire & the Humber" ),
    NE( "ne", "Northeast England" ),
    EM( "em", "East Midlands" ),
    EE( "ee", "East of England" ),
    SE( "se", "London & Southeast England" ),
    NW( "nw", "Northwest England" ),
    WM( "wm", "West Midlands" ),
    SW( "sw", "Southwest England" ),
    WL( "wl", "Wales" ),
    UK( "uk", "UK" );
    private final String id;
    private final String label;

    private static final List<Regions> REGIONS = Arrays.asList( values() );
    private static final Map<String, Regions> MAP = MapBuilder.<String, Regions>builder()
            ._synchronized()
            .addAll( Regions::getId, REGIONS )
            .build();

    private Regions( String id, String label )
    {
        this.id = id;
        this.label = label;
    }

    public String getId()
    {
        return id;
    }

    public String getLabel()
    {
        return label;
    }

    public static Regions lookup( String id )
    {
        return MAP.get( id );
    }

    public static Stream<Regions> stream()
    {
        return REGIONS.stream();
    }

    public static void forEach( Consumer<Regions> c )
    {
        REGIONS.forEach( c );
    }
}
