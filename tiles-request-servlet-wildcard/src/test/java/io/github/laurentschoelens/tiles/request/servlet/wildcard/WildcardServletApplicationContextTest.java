/*
 * $Id$
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 * Modified by Laurent Schoelens, 2026
 */
package io.github.laurentschoelens.tiles.request.servlet.wildcard;

import junit.framework.TestCase;

import io.github.laurentschoelens.tiles.request.ApplicationResource;
import io.github.laurentschoelens.tiles.request.locale.URLApplicationResource;
import org.easymock.EasyMock;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Vector;
import java.util.HashSet;

import jakarta.servlet.ServletContext;


/**
 * Tests {@link WildcardServletApplicationContext}.
 *
 * @version $Rev$ $Date$
 */
public class WildcardServletApplicationContextTest extends TestCase {

    /**
     * Number of properties container inside the test.properties file.
     */
    private static final int TEST_PROPERTIES_SIZE = 3;

    /**
     * The root Tiles application context.
     */
    private ServletContext servletContext;

    /**
     * The enhanced Tiles application context.
     */
    private WildcardServletApplicationContext context;

    /**
     * The original class loader.
     */
    private ClassLoader original;

    /** {@inheritDoc} */
    @Override
    public void setUp() {
        servletContext = EasyMock.createMock(ServletContext.class);
        original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(new MockClassLoader());
        } catch (MalformedURLException e) {
            throw new RuntimeException("Error when using the mock classloader");
        }
        context = new WildcardServletApplicationContext(servletContext);
    }

    /** {@inheritDoc} */
    @Override
    protected void tearDown() {
        Thread.currentThread().setContextClassLoader(original);
    }

    /**
     * Tests resource getting (legacy test).
     *
     * @throws IOException If something goes wrong.
     */
    public void testGetResources() throws IOException {
        String url = "/test.properties";
        URL u = URI.create("file:///tiles/test.properties").toURL();
        EasyMock.expect(servletContext.getResource(url)).andReturn(u).anyTimes();

        File dir = new File(".");
        EasyMock.expect(servletContext.getResource("/WEB-INF/")).andReturn(dir.toURI().toURL()).anyTimes();

        URL pomUrl = URI.create("file:///tiles/pom.xml").toURL();
        EasyMock.expect(servletContext.getResource("/WEB-INF/pom.xml")).andReturn(pomUrl).anyTimes();

        Set<String> elementSet = new HashSet<>();
        elementSet.add("/WEB-INF/pom.xml");
        EasyMock.expect(servletContext.getResourcePaths("/WEB-INF/")).andReturn(elementSet).anyTimes();
        EasyMock.replay(servletContext);

        assertEquals(new URLApplicationResource(u.toExternalForm(), u), context.getResource(url));
        assertEquals(new URLApplicationResource(pomUrl.toExternalForm(), pomUrl), context.getResource("/WEB-INF/*.xml"));
        assertEquals(TEST_PROPERTIES_SIZE, context.getResources("classpath*:/test.properties").size());

        assertEquals(1, context.getResources(
                "classpath*:/io/github/laurentschoelens/tiles/request/servlet/wildcard/*Test.class").size());
        EasyMock.verify(servletContext);
    }

    /**
     * Tests recursive wildcard scanning (** and ?) inside ServletContext subdirectories.
     *
     * @throws IOException If something goes wrong.
     */
    public void testServletContextRecursiveWildcards() throws IOException {
        Set<String> webInfPaths = new LinkedHashSet<>(Arrays.asList(
                "/WEB-INF/tiles-defs.xml",
                "/WEB-INF/sub/",
                "/WEB-INF/ignored.txt"
        ));
        Set<String> subPaths = new LinkedHashSet<>(Arrays.asList(
                "/WEB-INF/sub/tiles-sub1.xml",
                "/WEB-INF/sub/tiles-sub2.xml"
        ));

        URL rootDef = URI.create("file:///tiles/WEB-INF/tiles-defs.xml").toURL();
        URL subDef1 = URI.create("file:///tiles/WEB-INF/sub/tiles-sub1.xml").toURL();
        URL subDef2 = URI.create("file:///tiles/WEB-INF/sub/tiles-sub2.xml").toURL();

        EasyMock.expect(servletContext.getResourcePaths("/WEB-INF/")).andReturn(webInfPaths).anyTimes();
        EasyMock.expect(servletContext.getResourcePaths("/WEB-INF/sub/")).andReturn(subPaths).anyTimes();
        EasyMock.expect(servletContext.getResource("/WEB-INF/tiles-defs.xml")).andReturn(rootDef).anyTimes();
        EasyMock.expect(servletContext.getResource("/WEB-INF/sub/tiles-sub1.xml")).andReturn(subDef1).anyTimes();
        EasyMock.expect(servletContext.getResource("/WEB-INF/sub/tiles-sub2.xml")).andReturn(subDef2).anyTimes();
        EasyMock.replay(servletContext);

        Collection<ApplicationResource> allXmls = context.getResources("/WEB-INF/**/tiles*.xml");
        assertEquals(3, allXmls.size());

        Collection<ApplicationResource> singleCharMatch = context.getResources("/WEB-INF/sub/tiles-sub?.xml");
        assertEquals(2, singleCharMatch.size());

        EasyMock.verify(servletContext);
    }

    /**
     * Tests single classpath: prefix resolution (exact and wildcard) as well as JAR scanning.
     */
    public void testClasspathAndJarResources() {
        EasyMock.replay(servletContext);

        // Exact classpath: lookup returns only the first resource
        Collection<ApplicationResource> singleProp = context.getResources("classpath:test.properties");
        assertEquals(1, singleProp.size());

        // Wildcard classpath: lookup on compiled classes
        Collection<ApplicationResource> classMatch = context.getResources(
                "classpath:io/github/laurentschoelens/tiles/request/servlet/wildcard/*Test.class");
        assertEquals(1, classMatch.size());

        // Wildcard scanning inside a dependency JAR (EasyMock is on test classpath)
        Collection<ApplicationResource> jarClasses = context.getResources("classpath*:/org/easymock/*.class");
        assertFalse("Should find classes inside EasyMock JAR", jarClasses.isEmpty());

        EasyMock.verify(servletContext);
    }

    /**
     * Tests localized resource resolution via {@link WildcardServletApplicationContext#getResource(ApplicationResource, Locale)}.
     *
     * @throws IOException If something goes wrong.
     */
    public void testGetLocalizedResource() throws IOException {
        URL baseUrl = URI.create("file:///tiles/WEB-INF/tiles.xml").toURL();
        URL frUrl = URI.create("file:///tiles/WEB-INF/tiles_fr.xml").toURL();

        EasyMock.expect(servletContext.getResource("/WEB-INF/tiles_fr.xml")).andReturn(frUrl);
        EasyMock.replay(servletContext);

        ApplicationResource baseResource = new URLApplicationResource("/WEB-INF/tiles.xml", baseUrl);
        ApplicationResource localized = context.getResource(baseResource, Locale.FRENCH);

        assertNotNull(localized);
        assertEquals(new URLApplicationResource(frUrl.toExternalForm(), frUrl), localized);
        EasyMock.verify(servletContext);
    }

    /**
     * Tests URLs containing unencoded spaces (e.g. Windows paths) and edge cases (null, empty, missing, IOException).
     *
     * @throws IOException If something goes wrong.
     */
    public void testEdgeCasesAndUnencodedSpaces() throws IOException {
        // URL with unencoded space
        URL spacedUrl = new URL("file:///tiles spaces/webapps/app/WEB-INF/tiles.xml");
        EasyMock.expect(servletContext.getResourcePaths("/WEB-INF/"))
                .andReturn(Collections.singleton("/WEB-INF/tiles.xml"));
        EasyMock.expect(servletContext.getResource("/WEB-INF/tiles.xml")).andReturn(spacedUrl);

        // Missing resource
        EasyMock.expect(servletContext.getResource("/WEB-INF/missing.xml")).andReturn(null);

        // IOException thrown by ServletContext
        EasyMock.expect(servletContext.getResource("/WEB-INF/error.xml")).andThrow(new MalformedURLException("Invalid"));

        EasyMock.replay(servletContext);

        Collection<ApplicationResource> spacedResources = context.getResources("/WEB-INF/*.xml");
        assertEquals(1, spacedResources.size());

        assertNull(context.getResource((String) null));
        assertTrue(context.getResources("").isEmpty());
        assertNull(context.getResource("/WEB-INF/missing.xml"));
        assertTrue(context.getResources("/WEB-INF/error.xml").isEmpty());

        EasyMock.verify(servletContext);
    }

    /**
     * Tests that {@link WildcardServletApplicationContext#getResource(ApplicationResource, Locale)}
     * returns {@code null} when no localized resource exists.
     *
     * @throws IOException If something goes wrong.
     */
    public void testGetLocalizedResourceNotFound() throws IOException {
        URL baseUrl = URI.create("file:///tiles/WEB-INF/tiles.xml").toURL();

        EasyMock.expect(servletContext.getResource("/WEB-INF/tiles_de.xml")).andReturn(null);
        EasyMock.replay(servletContext);

        ApplicationResource baseResource = new URLApplicationResource("/WEB-INF/tiles.xml", baseUrl);
        ApplicationResource localized = context.getResource(baseResource, Locale.GERMAN);

        assertNull(localized);
        EasyMock.verify(servletContext);
    }

    /**
     * A mock class loader.
     */
    public static class MockClassLoader extends ClassLoader {

        /**
         * A vector of resources.
         */
        private final Vector<URL> testPropertiesResources;

        /**
         * Constructor.
         *
         * @throws MalformedURLException If the URL is not valid.
         */
        public MockClassLoader() throws MalformedURLException {
            testPropertiesResources = new Vector<>();
            testPropertiesResources.add(URI.create("file:///tiles/test/test.properties").toURL());
            testPropertiesResources.add(URI.create("file:///tiles/two/test.properties").toURL());
            testPropertiesResources.add(URI.create("file:///tiles/three/test.properties").toURL());
        }
        /** {@inheritDoc} */
        @Override
        public URL findResource(String name) {
            if ("test.properties".equals(name)) {
                return testPropertiesResources.firstElement();
            }
            return super.findResource(name);
        }
        /** {@inheritDoc} */
        @Override
        public Enumeration<URL> findResources(String path) throws IOException {
            if ("test.properties".equals(path)) {
                return testPropertiesResources.elements();
            }
            return super.findResources(path);
        }
    }
}