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
 *   Jan 11, 2022 (hornm): created
 */
package org.knime.core.webui.node;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

import org.knime.core.node.NodeFactory;
import org.knime.core.node.workflow.NativeNodeContainer;
import org.knime.core.node.workflow.NodeContainer;
import org.knime.core.webui.data.ApplyDataService;
import org.knime.core.webui.data.DataService;
import org.knime.core.webui.data.DataServiceProvider;
import org.knime.core.webui.data.InitialDataService;
import org.knime.core.webui.data.text.TextApplyDataService;
import org.knime.core.webui.data.text.TextDataService;
import org.knime.core.webui.data.text.TextInitialDataService;
import org.knime.core.webui.data.text.TextReExecuteDataService;
import org.knime.core.webui.node.util.NodeCleanUpCallback;
import org.knime.core.webui.page.Page;
import org.knime.core.webui.page.PageUtil;
import org.knime.core.webui.page.PageUtil.PageType;
import org.knime.core.webui.page.Resource;

/**
 * Common logic for classes that manage node ui extensions (e.g. views or dialogs).
 *
 * It manages
 * <p>
 * (i) the data services (i.e. {@link InitialDataService}, {@link DataService} and {@link ApplyDataService}). Data
 * service instances are only created once and cached until the respective node is disposed.
 * <p>
 * (ii) the page resources. I.e. keeps track of already accessed pages (to be able to also access resources from a
 * page-context - see {@link Page#getContext()}) and provides methods to determine page urls and paths.
 *
 * @author Martin Horn, KNIME GmbH, Konstanz, Germany
 */
@SuppressWarnings("java:S1170")
public abstract class AbstractNodeUIManager implements DataServiceManager, PageResourceManager {

    private final Map<NodeContainer, InitialDataService> m_initialDataServices = new WeakHashMap<>();

    private final Map<NodeContainer, DataService> m_dataServices = new WeakHashMap<>();

    private final Map<NodeContainer, ApplyDataService> m_applyDataServices = new WeakHashMap<>();

    private final Map<String, Page> m_pageMap = new HashMap<>();

    private final String m_pageKind = getPageType().toString();

    /*
     * Domain name used to identify resources requested for a node view.
     */
    private final String m_domainName = "org.knime.core.ui." + m_pageKind;

    private final String m_url = "http://" + m_domainName + "/";

    private final String m_nodeDebugPatternProp = "org.knime.ui.dev.node." + m_pageKind + ".url.factory-class";

    private final String m_nodeDebugUrlProp = "org.knime.ui.dev.node." + m_pageKind + ".url";

    /**
     * @param nc
     * @return the data service provide for the given node
     */
    protected abstract DataServiceProvider getDataServiceProvider(NodeContainer nc);

    /**
     * The type of pages the node ui manager implementation manages. E.g. whether these are view-pages or dialog-pages.
     *
     * @return the page type
     */
    protected abstract PageType getPageType();

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    public <S> Optional<S> getDataServiceOfType(final NodeContainer nc, final Class<S> dataServiceClass) {
        Object ds = null;
        if (InitialDataService.class.isAssignableFrom(dataServiceClass)) {
            ds = getInitialDataService(nc).orElse(null);
        } else if (DataService.class.isAssignableFrom(dataServiceClass)) {
            ds = getDataService(nc).orElse(null);
        } else if (ApplyDataService.class.isAssignableFrom(dataServiceClass)) {
            ds = getApplyDataService(nc).orElse(null);
        }
        if (ds != null && !dataServiceClass.isAssignableFrom(ds.getClass())) {
            ds = null;
        }
        return Optional.ofNullable((S)ds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String callTextInitialDataService(final NodeContainer nc) {
        var service = getInitialDataService(nc).filter(TextInitialDataService.class::isInstance).orElse(null);
        if (service != null) {
            return ((TextInitialDataService)service).getInitialData();
        } else {
            throw new IllegalStateException("No text initial data service available");
        }
    }

    private Optional<InitialDataService> getInitialDataService(final NodeContainer nc) {
        InitialDataService ds;
        if (!m_initialDataServices.containsKey(nc)) {
            ds = getDataServiceProvider(nc).createInitialDataService().orElse(null);
            m_initialDataServices.put(nc, ds);
        } else {
            ds = m_initialDataServices.get(nc);
        }
        return Optional.ofNullable(ds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String callTextDataService(final NodeContainer nc, final String request) {
        var service = getDataService(nc).filter(TextDataService.class::isInstance).orElse(null);
        if (service != null) {
            return ((TextDataService)service).handleRequest(request);
        } else {
            throw new IllegalStateException("No text data service available");
        }
    }

    private Optional<DataService> getDataService(final NodeContainer nc) {
        DataService ds;
        if (!m_dataServices.containsKey(nc)) {
            ds = getDataServiceProvider(nc).createDataService().orElse(null);
            m_dataServices.put(nc, ds);
        } else {
            ds = m_dataServices.get(nc);
        }
        return Optional.ofNullable(ds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void callTextApplyDataService(final NodeContainer nc, final String request) throws IOException {
        var service = getApplyDataService(nc).orElse(null);
        if (service instanceof TextReExecuteDataService) {
            ((TextReExecuteDataService)service).reExecute(request);
        } else if (service instanceof TextApplyDataService) {
            ((TextApplyDataService)service).applyData(request);
        } else {
            throw new IllegalStateException("No text apply data service available");
        }
    }

    private Optional<ApplyDataService> getApplyDataService(final NodeContainer nc) {
        ApplyDataService ds;
        if (!m_applyDataServices.containsKey(nc)) {
            ds = getDataServiceProvider(nc).createApplyDataService().orElse(null);
            m_applyDataServices.put(nc, ds);
        } else {
            ds = m_applyDataServices.get(nc);
        }
        return Optional.ofNullable(ds);
    }

    /**
     * Clears the page map.
     */
    protected final void clearPageMap() {
        m_pageMap.clear();
    }

    /**
     * For testing purposes only.
     *
     * @return the page map size
     */
    protected int getPageMapSize() {
        return m_pageMap.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<String> getPageUrl(final NativeNodeContainer nnc) {
        if (isRunAsDesktopApplication()) {
            var debugUrl = getDebugUrl(nnc.getNode().getFactory().getClass());
            if (debugUrl.isPresent()) {
                return debugUrl;
            }
            return Optional.of(m_url + getPagePathInternal(nnc));
        } else {
            return Optional.empty();
        }
    }

    /**
     * Optionally returns a debug url for a view (dialog etc.) which is controlled by a system property.
     *
     * @param nodeFactoryClass the node factory class to get the debug url for
     * @return a debug url or an empty optional of none is set
     */
    private Optional<String>
        getDebugUrl(@SuppressWarnings("rawtypes") final Class<? extends NodeFactory> nodeFactoryClass) {
        String pattern = System.getProperty(m_nodeDebugPatternProp);
        String url = System.getProperty(m_nodeDebugUrlProp);
        if (url == null) {
            return Optional.empty();
        }
        if (pattern == null || Pattern.matches(pattern, nodeFactoryClass.getName())) {
            return Optional.of(url);
        }
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDomainName() {
        return m_domainName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<String> getPagePath(final NativeNodeContainer nnc) {
        if (!isRunAsDesktopApplication()) {
            return Optional.of(getPagePathInternal(nnc));
        } else {
            return Optional.empty();
        }
    }

    private String getPagePathInternal(final NativeNodeContainer nnc) {
        return registerPage(nnc, getPage(nnc), getPageType());
    }

    private String registerPage(final NativeNodeContainer nnc, final Page page, final PageType pageKind) {
        var pageId = PageUtil.getPageId(nnc, page.isCompletelyStatic(), pageKind);
        if (m_pageMap.put(pageId, page) == null) {
            new NodeCleanUpCallback(nnc, () -> m_pageMap.remove(pageId));
        }
        return pageId + "/" + page.getRelativePath();
    }

    @Override
    public final Optional<Resource> getPageResource(final String resourceId) {
        var split = resourceId.indexOf("/");
        if (split <= 0) {
            return Optional.empty();
        }

        var pageId = resourceId.substring(0, split);
        var page = m_pageMap.get(pageId);
        if (page == null) {
            return Optional.empty();
        }

        var relPath = resourceId.substring(split + 1, resourceId.length());
        if (page.getRelativePath().equals(relPath)) {
            return Optional.of(page);
        } else {
            return Optional.ofNullable(page.getContext().get(relPath));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<Resource> getPageResourceFromUrl(final String url) {
        return getPageResource(getResourceIdFromUrl(url));
    }

    private String getResourceIdFromUrl(final String url) {
        return url.replace(m_url, "");
    }

    private static boolean isRunAsDesktopApplication() {
        return !"true".equals(System.getProperty("java.awt.headless"));
    }

}
