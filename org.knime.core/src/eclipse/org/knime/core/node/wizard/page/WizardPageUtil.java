/*
 * ------------------------------------------------------------------------
 *
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
 * ---------------------------------------------------------------------
 *
 * History
 *   Sep 27, 2021 (hornm): created
 */
package org.knime.core.node.wizard.page;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.knime.core.node.NodeLogger;
import org.knime.core.node.property.hilite.HiLiteHandler;
import org.knime.core.node.property.hilite.HiLiteManager;
import org.knime.core.node.property.hilite.HiLiteTranslator;
import org.knime.core.node.util.CheckUtils;
import org.knime.core.node.wizard.WizardNode;
import org.knime.core.node.wizard.page.WizardPage.WizardPageNodeInfo;
import org.knime.core.node.wizard.util.LayoutUtil;
import org.knime.core.node.workflow.NativeNodeContainer;
import org.knime.core.node.workflow.NodeContainer;
import org.knime.core.node.workflow.NodeID;
import org.knime.core.node.workflow.NodeID.NodeIDSuffix;
import org.knime.core.node.workflow.SingleNodeContainer;
import org.knime.core.node.workflow.SubNodeContainer;
import org.knime.core.node.workflow.SubnodeContainerLayoutStringProvider;
import org.knime.core.node.workflow.WorkflowLock;
import org.knime.core.node.workflow.WorkflowManager;
import org.knime.core.node.workflow.WorkflowManager.NodeModelFilter;
import org.knime.core.util.Pair;

/**
 * Utility methods to create wizard pages and retrieve wizard page nodes.
 *
 * @author Martin Horn, KNIME GmbH, Konstanz, Germany
 *
 * @since 4.5
 */
public final class WizardPageUtil {

    private static final NodeLogger LOGGER = NodeLogger.getLogger(WizardPageUtil.class);

    /** Filter passed to WFM serach methods to find only QF nodes that are to be displayed. */
    @SuppressWarnings("rawtypes")
    public static final NodeModelFilter<WizardNode> NOT_HIDDEN_FILTER = new NodeModelFilter<>() {
        @Override
        public boolean include(final WizardNode nodeModel) {
            return !nodeModel.isHideInWizard();
        }
    };

    private static final NodeModelFilter<WizardNode> NO_FILTER = new NodeModelFilter<>();

    /**
     * Checks whether a node (i.e. component) represents a wizard page.
     *
     * @param componentId the id of the node to check
     * @param wfm the workflow manager that contains the node to check
     * @return <code>true</code> if the node for the given id represents a wizard page, otherwise <code>false</code>
     *
     * @since 4.2
     */
    public static boolean isWizardPage(final WorkflowManager wfm, final NodeID componentId) {
        NodeContainer sourceNC;
        try {
            sourceNC = wfm.getNodeContainer(componentId);
        } catch (IllegalArgumentException e) { // NOSONAR
            return false;
        }
        // only consider nodes that are...SubNodes and...
        if (!(sourceNC instanceof SubNodeContainer)) {
            return false;
        }
        return isWizardPage((SubNodeContainer)sourceNC);
    }

    private static boolean isWizardPage(final SubNodeContainer snc) {
        // ...active and not hidden.
        if (snc.isInactive() || snc.isHideInWizard()) {
            return false;
        }
        // Now check if the active SubNode contains active QuickForm nodes:
        WorkflowManager subNodeWFM = snc.getWorkflowManager();
        Map<NodeID, NativeNodeContainer> wizardNodeSet = getWizardPageNodes(subNodeWFM);
        boolean allInactive = true;
        for (NativeNodeContainer nc : wizardNodeSet.values()) {
            if (!nc.isInactive()) {
                allInactive = false;
                break;
            }
        }
        if (allInactive) {
            // also consider nested SubNodes which might have views to display
            allInactive = getSubPageNodes(subNodeWFM).isEmpty();
        }
        return !allInactive;
    }

    /**
     * Creates the wizard page for a given node id. Throws exception if no wizard page available.
     *
     * @param manager the workflow that contains the component to create the wizard page for
     * @param subnodeID the node id for the subnode to create the wizard page for
     * @return The wizard page for the given node id
     */
    public static WizardPage createWizardPage(final WorkflowManager manager, final NodeID subnodeID) {
        if (subnodeID == null) {
            LOGGER.error("No node ID supplied for creating wizard page");
            return null;
        }
        assert manager.isLockedByCurrentThread(); // NOSONAR

        LinkedHashMap<NodeIDSuffix, NativeNodeContainer> resultMap = new LinkedHashMap<>();
        LinkedHashMap<NodeIDSuffix, WizardPageNodeInfo> infoMap = new LinkedHashMap<>();
        Set<HiLiteHandler> initialHiliteHandlerSet = new HashSet<>();
        SubNodeContainer subNC = manager.getNodeContainer(subnodeID, SubNodeContainer.class, true);
        LinkedHashMap<NodeIDSuffix, SubNodeContainer> sncMap = new LinkedHashMap<>();
        findNestedViewNodes(subNC, resultMap, infoMap, sncMap, initialHiliteHandlerSet);
        SubnodeContainerLayoutStringProvider layoutStringProvider = subNC.getSubnodeLayoutStringProvider();
        if (layoutStringProvider.isEmptyLayout() || layoutStringProvider.isPlaceholderLayout()) {
            try {
                WorkflowManager subWfm = subNC.getWorkflowManager();
                Map<NodeIDSuffix, SingleNodeContainer> viewMap = new LinkedHashMap<>();
                getWizardPageNodes(subWfm).entrySet().stream()
                    .forEach(e -> viewMap.put(toNodeIDSuffix(manager, e.getKey()), e.getValue()));
                Map<NodeID, SubNodeContainer> nestedSubs = getSubPageNodes(subWfm);
                nestedSubs.entrySet().stream()
                    .forEach(e -> viewMap.put(toNodeIDSuffix(manager, e.getKey()), e.getValue()));
                layoutStringProvider.setLayoutString(LayoutUtil.createDefaultLayout(viewMap));
            } catch (IOException ex) {
                LOGGER.error("Default page layout could not be created: " + ex.getMessage(), ex);
            }
        }
        try {
            LayoutUtil.expandNestedLayout(layoutStringProvider, subNC.getWorkflowManager());
        } catch (IOException ex) {
            LOGGER.error("Nested layouts could not be expanded: " + ex.getMessage(), ex);
        }
        try {
            NodeID containerID = NodeID
                .fromString(NodeIDSuffix.create(manager.getID(), subNC.getWorkflowManager().getID()).toString());
            LayoutUtil.addUnreferencedViews(layoutStringProvider, resultMap, sncMap, containerID);
        } catch (IOException ex) {
            LOGGER.error("Layout could not be amended by unreferenced views: " + ex.getMessage(), ex);
        }
        try {
            LayoutUtil.updateLayout(layoutStringProvider);
        } catch (Exception ex) { // NOSONAR
            LOGGER.error("Layout could not be updated: " + ex.getMessage(), ex);
        }
        Set<HiLiteHandler> knownHiLiteHandlers = new HashSet<>();
        Set<HiLiteTranslator> knownTranslators = new HashSet<>();
        Set<HiLiteManager> knownManagers = new HashSet<>();
        for (HiLiteHandler initialHandler : initialHiliteHandlerSet) {
            getHiLiteTranslators(initialHandler, knownHiLiteHandlers, knownTranslators, knownManagers);
        }
        List<HiLiteTranslator> translatorList =
            !knownTranslators.isEmpty() ? new ArrayList<>(knownTranslators) : null;
        List<HiLiteManager> managerList = !knownManagers.isEmpty() ? new ArrayList<>(knownManagers) : null;
        WizardPage page = new WizardPage(subnodeID, resultMap, layoutStringProvider.getLayoutString(),
            translatorList, managerList);
        page.setInfoMap(infoMap);
        return page;
    }

    /**
     * Collects all the node from the given workflow that contribute to a wizard page.
     *
     * @param wfm the workflow to collect the nodes from
     * @return map from node id to node
     *
     * @since 4.5
     */
    public static Map<NodeID, NativeNodeContainer> getWizardPageNodes(final WorkflowManager wfm) {
        return getWizardPageNodes(wfm, false);
    }

    /**
     * Collects all the nodes from the given workflow that contribute to a wizard page.
     *
     * @param wfm the workflow to collect the nodes from
     * @param recurseIntoComponents whether to recurse into contained components
     * @return map from node id to node
     *
     * @since 4.5
     */
    public static Map<NodeID, NativeNodeContainer> getWizardPageNodes(final WorkflowManager wfm,
        final boolean recurseIntoComponents) {
        return wfm.findNodes(WizardNode.class, NOT_HIDDEN_FILTER, false, recurseIntoComponents).keySet().stream()
            .collect(Collectors.toMap(id -> id, id -> (NativeNodeContainer)(recurseIntoComponents
                ? wfm.findNodeContainer(id) : wfm.getNodeContainer(id))));
    }

    /**
     * Collects all the nodes that (potentially) contribute to a wizard page, including the ones that are configured to
     * be hidden.
     *
     * @param wfm the workflow to collect the nodes from
     * @param recurseIntoComponents whether to recurse into contained components
     * @return map from node id to node
     */
    public static Map<NodeID, NativeNodeContainer> getAllWizardPageNodes(final WorkflowManager wfm,
        final boolean recurseIntoComponents) {
        return wfm.findNodes(WizardNode.class, NO_FILTER, false, recurseIntoComponents).keySet().stream()
            .collect(Collectors.toMap(id -> id, id -> (NativeNodeContainer)wfm.getNodeContainer(id)));
    }

    /**
     * Utility method to get a special set of successor nodes of the node denoted by the provided {@link NodeID}. The
     * start node id must denote a node contained in the component denoted by componentId.
     *
     * The stream of successors
     * <ul>
     * <li>includes the 'start' node itself</li>
     * <li>only contains nodes whose node model is of type {@link WizardNode}</li>
     * <li>does <i>not</i> include successors beyond the parent component (i.e. the component denoted by
     * componentId)</li>
     * <li>includes successors across component and metanode 'borders' (but not beyond the component page itself)</li>
     * </ul>
     *
     * @param wfm parent manager of the component denoted by componentId
     * @param componentId must be a component which represents the wizard page
     * @param startNodeId the node to get the successor nodes for
     * @return a stream of successor nodes represented by node id suffixes (relative to the project) and their node
     *         containers
     * @throws IllegalArgumentException if componentId doesn't denote a top-level component within the workflow manager
     *             scope or if startNodeId doesn't denote a node contained in the page
     *
     * @since 4.4
     */
    public static Stream<Pair<NodeIDSuffix, NodeContainer>> getSuccessorWizardPageNodesWithinComponent(
        final WorkflowManager wfm, final NodeID componentId, final NodeID startNodeId) {
        CheckUtils.checkArgument(wfm.getNodeContainer(componentId) instanceof SubNodeContainer,
            "Provided node id (%s) doesn't reference a component", componentId);
        try (WorkflowLock lock = wfm.lock()) {
            return getAllSuccessorNodesWithinComponent(componentId, wfm.findNodeContainer(startNodeId))//
                .filter(WizardPageUtil::isWizardNodeOrComponentOrMetanode)//
                .flatMap(nc -> {
                    if (nc instanceof NativeNodeContainer) {
                        return Stream.of(nc);
                    } else {
                        return getAllWizardNodesFromMetanodeOrComponent(wfm, nc);
                    }
                }).map(nc -> Pair.create(NodeIDSuffix.create(wfm.getProjectWFM().getID(), nc.getID()), nc));
        }
    }

    private static Stream<NodeContainer> getAllWizardNodesFromMetanodeOrComponent(final WorkflowManager wfm,
        final NodeContainer nc) {
        WorkflowManager ncWfm =
            nc instanceof WorkflowManager ? (WorkflowManager)nc : ((SubNodeContainer)nc).getWorkflowManager();
        return ncWfm.findNodes(WizardNode.class, new NodeModelFilter<WizardNode>(), true, true).keySet().stream()
            .map(wfm::findNodeContainer);
    }

    private static boolean isWizardNodeOrComponentOrMetanode(final NodeContainer nc) {
        return (nc instanceof NativeNodeContainer && ((NativeNodeContainer)nc).isModelCompatibleTo(WizardNode.class))
            || nc instanceof WorkflowManager || nc instanceof SubNodeContainer;
    }

    private static Stream<NodeContainer> getAllSuccessorNodesWithinComponent(final NodeID componentId,
        final NodeContainer startNode) {
        WorkflowManager wfm = startNode.getParent();
        Stream<NodeContainer> res =
            wfm.getNodeContainers(Collections.singleton(startNode.getID()), nc -> false, false, true).stream();

        WorkflowManager startNodeParent = startNode.getParent();
        NodeContainer startNodeLevelUp = startNodeParent.getDirectNCParent() instanceof SubNodeContainer
            ? (SubNodeContainer)startNodeParent.getDirectNCParent() : startNodeParent;
        if (!startNodeLevelUp.getID().equals(componentId) && !componentId.equals(startNode.getID())) {
            // recurse into the level above (but only if it's not the original component itself)
            Stream<NodeContainer> succ = getAllSuccessorNodesWithinComponent(componentId, startNodeLevelUp);
            // exclude start node
            succ = succ.filter(nc -> !nc.getID().equals(startNodeLevelUp.getID()));
            res = Stream.concat(res, succ);
        }
        return res;
    }

    /**
     * Retrieves all directly contained {@link SubNodeContainer} inside a given {@link WorkflowManager} which are wizard
     * pages (see {@link #isWizardPage(WorkflowManager, NodeID)}. Does not recursively look for nested subnodes.
     *
     * @param wfm The {@link WorkflowManager} of the parent container to look for contained subnodes.
     * @return A map of {@link NodeID} to {@link SubNodeContainer}
     * @since 3.7
     */
    public static Map<NodeID, SubNodeContainer> getSubPageNodes(final WorkflowManager wfm) {
        try (WorkflowLock lock = wfm.lock()) {
            Map<NodeID, SubNodeContainer> result = new LinkedHashMap<>();
            for (NodeContainer nc : wfm.getNodeContainers()) {
                if (nc instanceof SubNodeContainer && isWizardPage(wfm, nc.getID())) {
                    result.put(nc.getID(), (SubNodeContainer)nc);
                }
            }
            return result;
        }
    }

    /**
     * Collects different kind of infos for the 'wizard' nodes contained in a page (i.e. component). Nodes in nested
     * pages are recursively collected, too.
     *
     * @param subNC the page to collect the info for
     * @param resultMap the container for the collected {@link WizardNode}s, or <code>null</code> if it shouldn't be
     *            collected
     * @param infoMap the container for the collected {@link WizardPageNodeInfo}s, or <code>null</code> if it shouldn't
     *            be collected
     * @param sncMap the map of nested pages, or <code>null</code> if shouldn't be collected
     * @param initialHiliteHandlerSet collected hilite handlers or <code>null</code> if it shouldn't be collected
     */
    private static void findNestedViewNodes(final SubNodeContainer subNC,
        final Map<NodeIDSuffix, NativeNodeContainer> resultMap, final Map<NodeIDSuffix, WizardPageNodeInfo> infoMap,
        final Map<NodeIDSuffix, SubNodeContainer> sncMap, final Set<HiLiteHandler> initialHiliteHandlerSet) {
        WorkflowManager subWFM = subNC.getWorkflowManager();
        Map<NodeID, NativeNodeContainer> wizardNodeMap = getWizardPageNodes(subWFM);
        WorkflowManager projectWFM = subNC.getProjectWFM();
        for (Map.Entry<NodeID, NativeNodeContainer> entry : wizardNodeMap.entrySet()) {
            NodeContainer nc = subWFM.getNodeContainer(entry.getKey());
            if ((nc instanceof SingleNodeContainer) && ((SingleNodeContainer)nc).isInactive()) {
                //skip nodes in inactive branches
                continue;
            }
            NodeID.NodeIDSuffix idSuffix = NodeID.NodeIDSuffix.create(projectWFM.getID(), entry.getKey());
            if (infoMap != null) {
                WizardPageNodeInfo nodeInfo = new WizardPageNodeInfo();
                nodeInfo.setNodeName(nc.getName());
                nodeInfo.setNodeAnnotation(nc.getNodeAnnotation().toString());
                nodeInfo.setNodeState(nc.getNodeContainerState());
                nodeInfo.setNodeMessage(nc.getNodeMessage());
                infoMap.put(idSuffix, nodeInfo);
            }
            if (nc.getNodeContainerState().isExecuted() && resultMap != null) {
                //regular viewable nodes need to be executed
                resultMap.put(idSuffix, entry.getValue());
            }

            if (initialHiliteHandlerSet != null) {
                for (int i = 0; i < nc.getNrInPorts() - 1; i++) {
                    HiLiteHandler hiLiteHandler = entry.getValue().getNodeModel().getInHiLiteHandler(i);
                    if (hiLiteHandler != null) {
                        initialHiliteHandlerSet.add(hiLiteHandler);
                    }
                }
            }
        }
        Map<NodeID, SubNodeContainer> subnodeContainers = getSubPageNodes(subNC.getWorkflowManager());
        for (Entry<NodeID, SubNodeContainer> entry : subnodeContainers.entrySet()) {
            SubNodeContainer snc = entry.getValue();
            NodeID.NodeIDSuffix idSuffix = NodeID.NodeIDSuffix.create(projectWFM.getID(), snc.getID());
            if (sncMap != null) {
                sncMap.put(idSuffix, snc);
            }
            findNestedViewNodes(snc, resultMap, infoMap, sncMap, initialHiliteHandlerSet);
        }
    }

    private static NodeIDSuffix toNodeIDSuffix(final WorkflowManager wfm, final NodeID subnodeID) {
        return NodeIDSuffix.create(wfm.getID(), subnodeID);
    }

    private static void getHiLiteTranslators(final HiLiteHandler handler, final Set<HiLiteHandler> knownHiLiteHandlers,
        final Set<HiLiteTranslator> knownTranslators, final Set<HiLiteManager> knownManagers) {
        if (handler == null || !knownHiLiteHandlers.add(handler)) {
            return;
        }
        String handlerId = handler.getHiliteHandlerID().toString();
        LOGGER.debugWithFormat("Starting to iterate over hilite translators of handler %s", handlerId);
        Set<HiLiteTranslator> translatorsToCheck = handler.getHiLiteTranslators();
        Set<HiLiteTranslator> translatorsToFollow = new LinkedHashSet<>();
        for (HiLiteTranslator translator : translatorsToCheck) {
            if (translator != null && knownTranslators.add(translator)) {
                translatorsToFollow.add(translator);
            }
        }
        LOGGER.debugWithFormat("End iterating over hilite translators of handler %s (%d in total)", handlerId,
            translatorsToFollow.size());
        translatorsToFollow.forEach(
            translator -> followHiLiteTranslator(translator, knownHiLiteHandlers, knownTranslators, knownManagers));

        LOGGER.debugWithFormat("Starting to iterate over hilite managers of handler %s", handlerId);
        Set<HiLiteManager> managersToCheck = handler.getHiLiteManagers();
        Set<HiLiteManager> managersToFollow = new LinkedHashSet<>();
        for (HiLiteManager manager : managersToCheck) {
            if (manager != null && knownManagers.add(manager)) {
                managersToFollow.add(manager);
            }
        }
        LOGGER.debugWithFormat("End iterating over hilite managers of handler %s (%d in total)", handlerId,
            managersToFollow.size());
        managersToFollow
            .forEach(manager -> followHiLiteManager(manager, knownHiLiteHandlers, knownTranslators, knownManagers));
    }

    private static void followHiLiteTranslator(final HiLiteTranslator translator, final Set<HiLiteHandler> knownHiLiteHandlers,
        final Set<HiLiteTranslator> knownTranslators, final Set<HiLiteManager> knownManagers) {
        getHiLiteTranslators(translator.getFromHiLiteHandler(), knownHiLiteHandlers, knownTranslators, knownManagers);
        if (translator.getToHiLiteHandlers() != null) {
            for (HiLiteHandler toHiLiteHandler : translator.getToHiLiteHandlers()) {
                getHiLiteTranslators(toHiLiteHandler, knownHiLiteHandlers, knownTranslators, knownManagers);
            }
        }
    }

    private static void followHiLiteManager(final HiLiteManager manager, final Set<HiLiteHandler> knownHiLiteHandlers,
        final Set<HiLiteTranslator> knownTranslators, final Set<HiLiteManager> knownManagers) {
        getHiLiteTranslators(manager.getFromHiLiteHandler(), knownHiLiteHandlers, knownTranslators, knownManagers);
        if (manager.getToHiLiteHandlers() != null) {
            for (HiLiteHandler toHiLiteHandler : manager.getToHiLiteHandlers()) {
                getHiLiteTranslators(toHiLiteHandler, knownHiLiteHandlers, knownTranslators, knownManagers);
            }
        }
    }

    private WizardPageUtil() {
        // utility class
    }

}
