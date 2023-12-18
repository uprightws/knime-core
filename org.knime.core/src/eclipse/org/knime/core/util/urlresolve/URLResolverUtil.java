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
 *   9 Dec 2022 (leon.wenzler): created
 */
package org.knime.core.util.urlresolve;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import org.apache.hc.core5.net.URIBuilder;
import org.knime.core.util.URIPathEncoder;
import org.knime.core.util.exception.ResourceAccessException;
import org.knime.core.util.pathresolve.URIToFileResolve;

/**
 * Utility class for the KNIME URI resolving. Includes utilities for the space versions and conversion to URL.
 * Introduced to narrow Exception types from IOException to ResourceAccessException.
 *
 * @author Leon Wenzler, KNIME AG, Konstanz, Germany
 * @since 4.8
 */
public final class URLResolverUtil {

    /**
     * Wraps the IOException in a more specific ResourceAccessException.
     *
     * @param file
     * @return resolved canonical path
     * @throws ResourceAccessException
     */
    static String getCanonicalPath(final File file) throws ResourceAccessException {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            throw new ResourceAccessException("Failed to get the canonical path of file: " + e.getMessage(), e);
        }
    }

    /**
     * Converts the URI builder for the space URI to a URL. Used in KnimeUrlResolvers.
     *
     * @param uriBuilder the repository URI builder
     * @return built URL
     * @throws ResourceAccessException
     */
    static URL toURL(final URIBuilder uriBuilder) throws ResourceAccessException {
        try {
            return toURL(uriBuilder.build().normalize());
        } catch (URISyntaxException ex) {
            throw new ResourceAccessException("Could not build space URI: " + ex.getMessage(), ex);
        }
    }

    /**
     * Wraps the URI to URL conversion into a ResourceAccessException, used e.g. in {@link URIToFileResolve}.
     *
     * @param uri input URI
     * @return converted URL
     * @throws ResourceAccessException
     */
    public static URL toURL(final URI uri) throws ResourceAccessException {
        try {
            return URIPathEncoder.UTF_8.encodePathSegments(uri.toURL());
        } catch (MalformedURLException ex) {
            throw new ResourceAccessException("Cannot convert URI to URL: " + ex.getMessage(), ex);
        }
    }

    /**
     * Hides constructor.
     */
    private URLResolverUtil() {
    }
}
