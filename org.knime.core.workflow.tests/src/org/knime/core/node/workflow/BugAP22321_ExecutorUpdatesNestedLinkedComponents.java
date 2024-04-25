package org.knime.core.node.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;

import java.io.File;
import java.net.URI;
import java.util.Optional;

import org.apache.commons.lang3.Functions;
import org.junit.Before;
import org.junit.Test;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.util.pathresolve.ResolverUtil;
import org.knime.core.util.pathresolve.URIToFileResolve;
import org.osgi.util.tracker.ServiceTracker;

public class BugAP22321_ExecutorUpdatesNestedLinkedComponents extends WorkflowTestCase {

    private static final URI OUTER_URI = URI.create("knime://LOCAL/Outer");
    private static final URI INNER_URI = URI.create("knime://REMOTE/Inner");

    private static final int OUTER_SECONDS_BEFORE = 43;
    private static final int INNER_SECONDS_BEFORE = 13;

    private NodeID m_rootId;

    private File m_outerTemplateDir;
    private File m_innerTemplateDir;

    @Before
    public void setUp() throws Exception {
        m_rootId = loadAndSetWorkflow();
        final var root = getDefaultWorkflowDirectory();
        m_outerTemplateDir = new File(root, "data/Outer");
        m_innerTemplateDir = new File(root, "data/Inner");
    }

    /**
     * <b>Bug report description:</b>
     * <p>
     * If a workflow contains an outer linked component (out-of-date) which contains an inner linked component
     * (up-to-date, linking to Server or Hub), calling WorkflowManager#updateMetaNodeLinks(...) on the WorkflowManager
     * representing the loaded workflow fails with a NullPointerException. This prevents the workflow from being loaded
     * as a job in an Executor if the update_metanodelinks_on_load flag is set.
     */
    @Test
    public void testUpdateTemplatesTopLevel() throws Exception {
        final var wfm = getManager();
        final var outerComponentId = m_rootId.createChild(3);
        final var innerComponentId = outerComponentId.createChild(0).createChild(3);

        // check that the last-updated times are as expected before the update
		final var outerBefore = (SubNodeContainer)wfm.findNodeContainer(outerComponentId);
        assertEquals(OUTER_SECONDS_BEFORE, outerBefore.getTemplateInformation().getTimestamp().getSecond());
        final var innerBefore = (SubNodeContainer)wfm.findNodeContainer(innerComponentId);
        assertEquals(INNER_SECONDS_BEFORE, innerBefore.getTemplateInformation().getTimestamp().getSecond());

        // we mock the component template resolution because the bug only appears with a remote (HTTP[S]) template
        final var mockedResolver = mock(URIToFileResolve.class);

        // the outer component is local, return its template path
        when(mockedResolver.resolveToFile(eq(OUTER_URI))).thenReturn(m_outerTemplateDir);
        // the inner component is remote, no local template available
        when(mockedResolver.resolveToFile(eq(INNER_URI))).thenReturn(null);

        // the inner component doesn't have an update
        when(mockedResolver.resolveToLocalOrTempFileConditional(eq(INNER_URI), any(), notNull())) //
            .thenReturn(Optional.empty());
        // if the inner component is requested unconditionally, return the template path
        when(mockedResolver.resolveToLocalOrTempFile(eq(INNER_URI), any())).thenReturn(m_innerTemplateDir);

        // run the same code used by Hub/Server executors, using the mocked resolver
        runWithResolver(mockedResolver, () -> {
            wfm.updateMetaNodeLinks(new WorkflowLoadHelper(true), true, new ExecutionMonitor());
        });

        // check that the last-updated times are as expected (Outer updated) after the update
        final var outerAfter = (SubNodeContainer)wfm.findNodeContainer(outerComponentId);
        assertEquals(OUTER_SECONDS_BEFORE + 1, outerAfter.getTemplateInformation().getTimestamp().getSecond());
        final var innerAfter = (SubNodeContainer)wfm.findNodeContainer(innerComponentId);
        assertEquals(INNER_SECONDS_BEFORE, innerAfter.getTemplateInformation().getTimestamp().getSecond());
    }


    private void runWithResolver(URIToFileResolve mockedResolver,
            final Functions.FailableRunnable<Exception> body) throws Exception {
        // set up fields to inject mocked `URIToFileResolve` into `ResolverUtil`
        final var resolverTrackerField = ResolverUtil.class.getDeclaredField("serviceTracker");
        resolverTrackerField.setAccessible(true);
        final var cachedResolverField = ServiceTracker.class.getDeclaredField("cachedService");
        cachedResolverField.setAccessible(true);

        // extract the service tracker for the `URIToFileResolve` service
        final var staticResolverTracker = resolverTrackerField.get(null);
        // remember the previously cached service object
        final var oldResolver = cachedResolverField.get(staticResolverTracker);
        try {
            // temporarily inject alternative service into the cache and run method body
            cachedResolverField.set(staticResolverTracker, mockedResolver);
            body.run();
        } finally {
            // restore the old cached service object
            cachedResolverField.set(staticResolverTracker, oldResolver);
        }
    }
}
