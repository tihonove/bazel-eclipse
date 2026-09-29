/*-
 * LOCAL PATCH (tihonove fork): incremental refresh support.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package com.salesforce.bazel.eclipse.core.classpath;

import static java.nio.file.Files.isRegularFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.salesforce.bazel.eclipse.core.BazelCoreSharedContstants;
import com.salesforce.bazel.eclipse.core.model.BazelPackage;
import com.salesforce.bazel.eclipse.core.model.BazelProject;
import com.salesforce.bazel.eclipse.core.model.BazelWorkspace;

/**
 * Remembers, per Bazel project, a digest of the build configuration the project's classpath was last computed from.
 * <p>
 * For package (and target) projects this is the content of the package's <code>BUILD</code> file. For the workspace
 * project this is the content of the project view (<code>.bazelproject</code>) including all files it imports.
 * </p>
 * <p>
 * The digests allow an incremental refresh ({@link com.salesforce.bazel.eclipse.core.model.RefreshProjectsJob}) to
 * find out which projects need their targets and classpath re-computed after files changed outside the IDE (branch
 * switch, pull) without re-synchronizing the whole workspace.
 * </p>
 */
public class BuildFileDigestStore {

    private static Logger LOG = LoggerFactory.getLogger(BuildFileDigestStore.class);

    private static final String FILE_NAME = "build-file-digests.properties";

    /**
     * Format marker. Files without it were written by 1.4.4, which recorded the current BUILD files as baseline on
     * its first run without knowing whether the saved classpaths were computed from them; those entries are discarded.
     */
    private static final String FORMAT_KEY = "_format";
    private static final String FORMAT_VERSION = "2";

    private static final Pattern PROJECT_VIEW_IMPORT = Pattern.compile("^\\s*(?:try_)?import\\s*:?\\s*(\\S+)\\s*$");

    /**
     * Computes a digest over the content of the given files.
     * <p>
     * Missing files contribute their absence to the digest, so a file that disappears changes the digest.
     * </p>
     */
    public static String computeDigest(List<Path> files) throws IOException {
        try {
            var md = MessageDigest.getInstance("MD5");
            for (Path file : files) {
                md.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                if (isRegularFile(file)) {
                    md.update(Files.readAllBytes(file));
                } else {
                    md.update("<missing>".getBytes(StandardCharsets.UTF_8));
                }
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 not available", e);
        }
    }

    /**
     * Computes the digest of the build configuration relevant for a project.
     *
     * @return the digest, or <code>null</code> if the project has no build file on disk (eg., the package was removed)
     */
    public static String computeDigest(BazelProject project) throws CoreException, IOException {
        if (project.isWorkspaceProject()) {
            return computeDigest(getProjectViewFiles(project.getBazelWorkspace()));
        }
        var buildFile = findBuildFile(project);
        if (buildFile == null) {
            return null;
        }
        return computeDigest(List.of(buildFile));
    }

    /**
     * Finds the BUILD file of a package or target project on disk (without consulting Bazel).
     *
     * @return the build file or <code>null</code> if there is none
     */
    public static Path findBuildFile(BazelProject project) throws CoreException {
        var packageLocation = getPackageLocation(project);
        if (packageLocation == null) {
            return null;
        }
        for (String name : List.of(
            BazelCoreSharedContstants.FILE_NAME_BUILD_BAZEL,
            BazelCoreSharedContstants.FILE_NAME_BUILD)) {
            var candidate = packageLocation.resolve(name);
            if (isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * @return the location of the package a package or target project belongs to (<code>null</code> for the workspace
     *         project)
     */
    public static Path getPackageLocation(BazelProject project) throws CoreException {
        BazelPackage bazelPackage;
        if (project.isPackageProject()) {
            bazelPackage = project.getBazelPackage();
        } else if (project.isTargetProject()) {
            bazelPackage = project.getBazelTarget().getBazelPackage();
        } else {
            return null;
        }
        return bazelPackage.getLocation().toPath();
    }

    /**
     * @return the project view file of the workspace followed by all the files it (transitively) imports
     */
    public static List<Path> getProjectViewFiles(BazelWorkspace workspace) throws IOException {
        Set<Path> result = new LinkedHashSet<>();
        var workspaceRoot = workspace.getLocation().toPath();
        collectProjectViewFiles(workspace.getBazelProjectViewFile(), workspaceRoot, result);
        return new ArrayList<>(result);
    }

    private static void collectProjectViewFiles(Path projectViewFile, Path workspaceRoot, Set<Path> result)
            throws IOException {
        if (!result.add(projectViewFile) || !isRegularFile(projectViewFile)) {
            return;
        }
        for (String line : Files.readAllLines(projectViewFile, StandardCharsets.UTF_8)) {
            var matcher = PROJECT_VIEW_IMPORT.matcher(line);
            if (matcher.matches()) {
                // imports are relative to the workspace root (see BazelProjectFileReader)
                collectProjectViewFiles(workspaceRoot.resolve(matcher.group(1)), workspaceRoot, result);
            }
        }
    }

    private final File stateFile;

    private Properties digests;

    public BuildFileDigestStore(File stateLocationDirectory) {
        stateFile = new File(stateLocationDirectory, FILE_NAME);
    }

    /**
     * @return the remembered digest for a project (<code>null</code> if none was recorded yet)
     */
    public synchronized String get(String projectName) {
        return getDigests().getProperty(projectName);
    }

    private Properties getDigests() {
        if (digests == null) {
            digests = new Properties();
            if (stateFile.isFile()) {
                try (InputStream in = new BufferedInputStream(new FileInputStream(stateFile))) {
                    digests.load(in);
                } catch (IOException e) {
                    LOG.warn("Unable to read build file digests from '{}': {}", stateFile, e.getMessage());
                }
                if (!FORMAT_VERSION.equals(digests.getProperty(FORMAT_KEY))) {
                    LOG.info("Discarding build file digests of an older format from '{}'", stateFile);
                    digests.clear();
                }
            }
        }
        return digests;
    }

    /**
     * Records a digest for a project and persists the store.
     */
    public synchronized void put(String projectName, String digest) {
        var current = getDigests();
        if (digest.equals(current.getProperty(projectName))) {
            return;
        }
        current.setProperty(projectName, digest);
        save();
    }

    /**
     * Forgets the digest of a project (eg., when it gets deleted).
     */
    public synchronized void remove(String projectName) {
        if (getDigests().remove(projectName) != null) {
            save();
        }
    }

    private void save() {
        digests.setProperty(FORMAT_KEY, FORMAT_VERSION);
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(stateFile))) {
            digests.store(out, "Digests of BUILD files / project views the Bazel classpath containers were computed from");
        } catch (IOException e) {
            LOG.warn("Unable to save build file digests to '{}': {}", stateFile, e.getMessage());
        }
    }
}
