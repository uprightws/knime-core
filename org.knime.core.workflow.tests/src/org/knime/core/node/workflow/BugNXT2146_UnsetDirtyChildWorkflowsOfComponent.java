/*
 * ------------------------------------------------------------------------
 *  Copyright by KNIME AG, Zurich, Switzerland
 *  Website: http://www.knime.com; Email: contact@knime.com
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME AG herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ------------------------------------------------------------------------
 */
package org.knime.core.node.workflow;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.awaitility.Awaitility;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.workflow.NodeID.NodeIDSuffix;
import org.knime.core.node.workflow.WorkflowEvent.Type;
import org.knime.core.node.workflow.WorkflowPersistor.MetaNodeLinkUpdateResult;
import org.knime.core.node.workflow.contextv2.WorkflowContextV2;
import org.knime.core.util.FileUtil;
import org.mockito.Mockito;

/**
 * 
 * @author Martin Horn, KNIME GmbH, Konstanz, Germany
 */
public class BugNXT2146_UnsetDirtyChildWorkflowsOfComponent extends WorkflowTestCase {

	@Test
	public void testUnsetDirtyChildWorkflowsOfComponentsOnSave() throws Exception {
		// load component
		var componentDir = FileUtil.createTempDir(getClass().getSimpleName());
		FileUtil.copyDir(getDefaultWorkflowDirectory(), componentDir);
		var loadHelper = new WorkflowLoadHelper(true, true,
				WorkflowContextV2.forTemporaryWorkflow(componentDir.toPath(), null));
		var loadResult = loadComponent(componentDir, new ExecutionMonitor(), loadHelper);

		// get nodes
		var componentProject = (SubNodeContainer) loadResult.getLoadedInstance();
		var nestedMetanode = (WorkflowManager) componentProject.getWorkflowManager()
				.findNodeContainer(NodeIDSuffix.fromString("0:5:0:4").prependParent(componentProject.getID()));
		var nestedComponent = (SubNodeContainer) componentProject.getWorkflowManager()
				.findNodeContainer(NodeIDSuffix.fromString("0:6:5").prependParent(componentProject.getID()));

		// set dirty and check
		nestedMetanode.setDirty();
		nestedComponent.getWorkflowManager().setDirty();
		assertDirtyState(true, componentProject, componentProject.getWorkflowManager(), nestedMetanode, nestedComponent,
				nestedComponent.getWorkflowManager());

		// save component and check that everything is clean again and the respective
		// events have been emitted
		var componentWorkflowListener = mock(WorkflowListener.class);
		componentProject.getWorkflowManager().addListener(componentWorkflowListener);
		var nestedMetanodeWorkflowListener = mock(WorkflowListener.class);
		nestedMetanode.addListener(nestedMetanodeWorkflowListener);
		var nestedComponentWorkflowListener = mock(WorkflowListener.class);
		nestedComponent.getWorkflowManager().addListener(nestedComponentWorkflowListener);
		componentProject.saveAsTemplate(componentDir, new ExecutionMonitor()); // save
		assertDirtyState(false, componentProject, componentProject.getWorkflowManager(), nestedMetanode,
				nestedComponent, nestedComponent.getWorkflowManager());
		await().untilAsserted(() -> {
			verify(componentWorkflowListener).workflowChanged(argThat(e -> Type.WORKFLOW_CLEAN == e.getType()));
			verify(nestedMetanodeWorkflowListener).workflowChanged(argThat(e -> Type.WORKFLOW_CLEAN == e.getType()));
			verify(nestedComponentWorkflowListener).workflowChanged(argThat(e -> Type.WORKFLOW_CLEAN == e.getType()));
		});
	}

	private static void assertDirtyState(boolean isDirty, NodeContainer... ncs) {
		for (var nc : ncs) {
			assertThat("expected dirty state of node %s: %s".formatted(nc.getNameWithID(), isDirty), nc.isDirty(),
					is(isDirty));
		}
	}

}
