package eu.dnetlib.iis.workflows.transformers.export.citationwithsentimentlabels;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import eu.dnetlib.iis.IntegrationTest;
import eu.dnetlib.iis.core.AbstractOozieWorkflowTestCase;
import eu.dnetlib.iis.core.OozieWorkflowTestConfiguration;

/**
 * 
 * @author mhorst
 *
 */
@Category(IntegrationTest.class)
public class WorkflowTest extends AbstractOozieWorkflowTestCase {

    @Test
    public void testWorkflow() throws Exception {
        OozieWorkflowTestConfiguration wf = new OozieWorkflowTestConfiguration();
        wf.setTimeoutInSeconds(720);
        testWorkflow(
                "eu/dnetlib/iis/workflows/transformers/export/citationwithsentimentlabels/sampledataproducer",
                wf);
    }

}
