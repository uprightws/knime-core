/* @(#)$RCSfile$ 
 * $Revision$ $Date$ $Author$
 * 
 * -------------------------------------------------------------------
 * This source code, its documentation and all appendant files
 * are protected by copyright law. All rights reserved.
 * 
 * Copyright, 2003 - 2006
 * Universitaet Konstanz, Germany.
 * Lehrstuhl fuer Angewandte Informatik
 * Prof. Dr. Michael R. Berthold
 * 
 * You may not modify, publish, transmit, transfer or sell, reproduce,
 * create derivative works from, distribute, perform, display, or in
 * any way exploit any of the content, in whole or in part, except as
 * otherwise expressly permitted in writing by the copyright owner.
 * -------------------------------------------------------------------
 * 
 * History
 *   04.07.2005 (Florian Georg): created
 */
package org.knime.workbench.core;

import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.knime.core.eclipseUtil.ClassCreator;
import org.osgi.framework.Bundle;


/**
 * Class creator, used inside Eclipse to load classes for the KNIME core. We
 * need this to lookup classes from the contributing plugins.
 * 
 * This class is kinda hack ... it first tries to load classes from the core and
 * editor plugins. If this fails, the requested class is tried to be loaded from
 * all plugins that contribute extensions to a given extension point ID. In our
 * case, this is normally the "knime.nodes" extension point, so that we can load
 * <code>NodeFactory</code> classes from contributing plugins.
 * 
 * @author Florian Georg, University of Konstanz
 */
public class EclipseClassCreator implements ClassCreator {

    private IExtension[] m_extensions;

    /**
     * Constructor.
     * 
     * @param pointID The extension point to process
     */
    public EclipseClassCreator(final String pointID) {
        IExtensionRegistry registry = Platform.getExtensionRegistry();
        IExtensionPoint point = registry.getExtensionPoint(pointID);
        if (point == null) {
            throw new IllegalStateException("Invalid extension point : "
                    + pointID);

        }
        m_extensions = point.getExtensions();
    }

    /**
     * Tries to resolve the class by asking all plugins that contribute to our
     * extension point.
     * 
     * @param className The class to lookup
     * @return class, or <code>null</code>
     */
    public Class createClass(final String className) {

        Class clazz = null;

        // first, try the core and editor
        try {
            Bundle p = KNIMECorePlugin.getDefault().getBundle();
            clazz = p.loadClass(className);
            return clazz;
        } catch (Exception ex) {
            try {
                Bundle p = Platform
                        .getBundle("org.knime.workbench.editor");
                clazz = p.loadClass(className);
                return clazz;
            } catch (Exception e) {
                // ignore
            }
        }

        /**
         * Look at all extensions, and try to load class from the plugin
         */
        for (int i = 0; i < m_extensions.length && clazz == null; i++) {
            IExtension ext = m_extensions[i];
            String pluginID = ext.getNamespace();

            try {
                Bundle p = Platform.getBundle(pluginID);
                clazz = p.loadClass(className);
            } catch (Exception ex) {
                // ignore
            }

        }

        return clazz;

    }

}
