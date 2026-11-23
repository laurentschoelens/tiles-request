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

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.servlet.ServletContext;
import io.github.laurentschoelens.tiles.request.ApplicationResource;
import io.github.laurentschoelens.tiles.request.locale.URLApplicationResource;
import io.github.laurentschoelens.tiles.request.servlet.ServletApplicationContext;

/**
 * Servlet-based implementation of the TilesApplicationContext interface that
 * can resolve resources even using wildcards.
 *
 * @version $Rev$ $Date$
 */
public class WildcardServletApplicationContext extends ServletApplicationContext {
    /** Prefix for all classpath resource in spring's style. */
    private static final String CLASSPATH_ALL_PREFIX = "classpath*:";
    /** Prefix for classpath resource in spring's style. */
    private static final String CLASSPATH_PREFIX = "classpath:";

    /**
     * Current servlet context.
     */
    private final ServletContext servletContext;

    /**
     * Constructor.
     *
     * @param servletContext The servlet context.
     */
    public WildcardServletApplicationContext(ServletContext servletContext) {
        super(servletContext);
        this.servletContext = servletContext;
    }

    /** {@inheritDoc} */
    @Override
    public ApplicationResource getResource(String localePath) {
        Collection<ApplicationResource> urlSet = getResources(localePath);
        if (urlSet != null && !urlSet.isEmpty()) {
            return urlSet.iterator().next();
        }
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public ApplicationResource getResource(ApplicationResource base, Locale locale) {
        Collection<ApplicationResource> urlSet = getResources(base.getLocalePath(locale));
        if (urlSet != null && !urlSet.isEmpty()) {
            return urlSet.iterator().next();
        }
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public Collection<ApplicationResource> getResources(String path) {
        if (path == null || path.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            Collection<URL> urls = resolveUrls(path);
            if (urls.isEmpty()) {
                return Collections.emptyList();
            }

            List<ApplicationResource> resourceList = new ArrayList<>(urls.size());
            for (URL url : urls) {
                resourceList.add(new URLApplicationResource(url.toExternalForm(), url));
            }
            return resourceList;
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private Collection<URL> resolveUrls(String locationPattern) throws IOException {
        if (locationPattern.startsWith(CLASSPATH_ALL_PREFIX)) {
            return findClasspathResources(locationPattern.substring(CLASSPATH_ALL_PREFIX.length()), true);
        } else if (locationPattern.startsWith(CLASSPATH_PREFIX)) {
            return findClasspathResources(locationPattern.substring(CLASSPATH_PREFIX.length()), false);
        } else {
            return findServletContextResources(locationPattern);
        }
    }

    private Collection<URL> findServletContextResources(String locationPattern) throws IOException {
        String normalizedPath = locationPattern.startsWith("/") ? locationPattern : "/" + locationPattern;

        if (!isPattern(normalizedPath)) {
            URL url = servletContext.getResource(normalizedPath);
            return url != null ? Collections.singletonList(url) : Collections.emptyList();
        }

        Map<URI, URL> result = new LinkedHashMap<>();
        String rootDir = determineRootDir(normalizedPath);
        Pattern regex = convertAntToRegex(normalizedPath);

        scanServletContext(rootDir, regex, result);
        return result.values();
    }

    private void scanServletContext(String currentPath, Pattern regex, Map<URI, URL> result) throws IOException {
        Set<String> paths = servletContext.getResourcePaths(currentPath);
        if (paths == null) {
            return;
        }

        for (String path : paths) {
            if (path.endsWith("/")) {
                scanServletContext(path, regex, result);
            } else if (regex.matcher(path).matches()) {
                URL url = servletContext.getResource(path);
                addUrl(result, url);
            }
        }
    }

    private Collection<URL> findClasspathResources(String locationPattern, boolean allClasspaths) throws IOException {
        String cleanPattern = locationPattern.startsWith("/") ? locationPattern.substring(1) : locationPattern;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = getClass().getClassLoader();
        }

        Map<URI, URL> result = new LinkedHashMap<>();

        if (!isPattern(cleanPattern)) {
            if (allClasspaths) {
                Enumeration<URL> urls = cl.getResources(cleanPattern);
                while (urls.hasMoreElements()) {
                    addUrl(result, urls.nextElement());
                }
            } else {
                addUrl(result, cl.getResource(cleanPattern));
            }
            return result.values();
        }

        String rootDir = determineRootDir(cleanPattern);
        Pattern regex = convertAntToRegex(cleanPattern);

        Enumeration<URL> rootUrls = cl.getResources(rootDir);
        while (rootUrls.hasMoreElements()) {
            URL rootUrl = rootUrls.nextElement();
            String protocol = rootUrl.getProtocol();

            if ("file".equals(protocol)) {
                scanFileDirectory(rootUrl, rootDir, regex, result);
            } else if ("jar".equals(protocol)) {
                scanJarFile(rootUrl, regex, result);
            }

            if (!allClasspaths && !result.isEmpty()) {
                break;
            }
        }
        return result.values();
    }

    private void scanFileDirectory(
            URL rootUrl, String rootDir, Pattern regex, Map<URI, URL> result) throws IOException {
        try {
            Path startPath = Paths.get(rootUrl.toURI());
            if (!Files.exists(startPath)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(startPath)) {
                walk.filter(Files::isRegularFile).forEach(file -> {
                    String relative = startPath.relativize(file).toString().replace('\\', '/');
                    String fullClasspathPath = rootDir.isEmpty() ? relative : rootDir + relative;
                    if (regex.matcher(fullClasspathPath).matches()) {
                        try {
                            URI uri = file.toUri();
                            result.putIfAbsent(uri, uri.toURL());
                        } catch (IOException ignored) {
                        }
                    }
                });
            }
        } catch (URISyntaxException e) {
            throw new IOException("Invalid URI for resource: " + rootUrl, e);
        }
    }

    private void scanJarFile(URL rootUrl, Pattern regex, Map<URI, URL> result) throws IOException {
        JarURLConnection jarConn = (JarURLConnection) rootUrl.openConnection();
        jarConn.setUseCaches(false);
        try (JarFile jarFile = jarConn.getJarFile()) {
            String jarUrlPrefix = "jar:" + jarConn.getJarFileURL().toExternalForm() + "!/";
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.isDirectory() && regex.matcher(entry.getName()).matches()) {
                    URI uri = URI.create(jarUrlPrefix + entry.getName());
                    result.putIfAbsent(uri, uri.toURL());
                }
            }
        }
    }

    private void addUrl(Map<URI, URL> result, URL url) {
        if (url != null) {
            try {
                result.putIfAbsent(url.toURI(), url);
            } catch (URISyntaxException e) {
                // Fallback si l'URL contient des caractères non encodés (ex: espaces)
                result.putIfAbsent(URI.create(url.toExternalForm().replace(" ", "%20")), url);
            }
        }
    }

    private String determineRootDir(String location) {
        int rootDirEnd = location.length();
        while (rootDirEnd > 0 && isPattern(location.substring(0, rootDirEnd))) {
            rootDirEnd = location.lastIndexOf('/', rootDirEnd - 2) + 1;
        }
        return location.substring(0, rootDirEnd);
    }

    private boolean isPattern(String path) {
        return path.indexOf('*') != -1 || path.indexOf('?') != -1;
    }

    private Pattern convertAntToRegex(String antPattern) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < antPattern.length()) {
            if (antPattern.startsWith("**/", i)) {
                sb.append("(?:.+/)?");
                i += 3;
            } else if (antPattern.startsWith("**", i)) {
                sb.append(".*");
                i += 2;
            } else {
                char c = antPattern.charAt(i);
                if (c == '*') {
                    sb.append("[^/]*");
                } else if (c == '?') {
                    sb.append("[^/]");
                } else if ("\\.[]{}()+-^$|".indexOf(c) != -1) {
                    sb.append('\\').append(c);
                } else {
                    sb.append(c);
                }
                i++;
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString());
    }
}
