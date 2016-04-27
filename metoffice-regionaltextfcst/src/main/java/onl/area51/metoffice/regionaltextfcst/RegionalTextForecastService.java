/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package onl.area51.metoffice.regionaltextfcst;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import onl.area51.httpd.action.ActionRegistry;
import onl.area51.metoffice.DataPoint;
import uk.trainwatch.scheduler.Cron;

/**
 *
 * @author peter
 */
@ApplicationScoped
public class RegionalTextForecastService
{

    @Inject
    private DataPoint dataPoint;

    public void deploy( @Observes ActionRegistry registry )
    {

    }

    @PostConstruct
    void start()
    {
        try {
            reload();
        }
        catch( IOException |
               URISyntaxException ex ) {
            Logger.getLogger( RegionalTextForecastService.class.getName() ).log( Level.SEVERE, null, ex );
        }
    }

    @Cron("0 5/5 4-8,16-20 * * ? *")
    public void reload()
            throws IOException,
                   URISyntaxException
    {
        dataPoint.call( "txt/wxfcs/regionalforecast", "capabilities" );
    }
}
