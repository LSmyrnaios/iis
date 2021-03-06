package eu.dnetlib.iis.wf.referenceextraction.softwareurl;

import java.io.Serializable;

import eu.dnetlib.iis.wf.referenceextraction.ContentRetrieverResponse;

/**
 * Content retriever module obtaining page content for given url.
 * 
 * @author mhorst
 *
 */
public interface ContentRetriever extends Serializable {

    /**
     * Retrieves content for given url. Should never return null. 
     * @param url location the page content should be retrieved from
     */
    ContentRetrieverResponse retrieveUrlContent(CharSequence url);
    
}
