/*-
 * LOCAL PATCH (tihonove fork): incremental refresh of Bazel projects.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package com.salesforce.bazel.eclipse.core.model;

import static java.lang.String.format;
import static java.util.stream.Collectors.joining;
import static org.eclipse.core.resources.IResource.DEPTH_INFINITE;
import static org.eclipse.core.resources.IResource.DEPTH_ONE;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.model.primitives.TargetExpression;
import com.google.idea.blaze.base.model.primitives.WorkspacePath;
import com.salesforce.bazel.eclipse.core.BazelCoreSharedContstants;
import com.salesforce.bazel.eclipse.core.classpath.BazelClasspathManager;
import com.salesforce.bazel.eclipse.core.classpath.BuildFileDigestStore;
import com.salesforce.bazel.eclipse.core.classpath.InitializeOrRefreshClasspathJob;
import com.salesforce.bazel.eclipse.core.model.discovery.TargetDiscoveryAndProvisioningExtensionLookup;
import com.salesforce.bazel.eclipse.core.util.trace.TracingSubMonitor;

/**
 * Incrementally brings the Eclipse model of a Bazel workspace up to date with the file system.
 * <p>
 * This is the cheap alternative to {@link SynchronizeProjectViewJob} after files changed <em>outside</em> the IDE
 * (branch switch, <code>git pull</code>, files generated while the language server was not running). It does:
 * </p>
 * <ol>
 * <li>Refresh the resources of all Bazel projects from disk. Eclipse only learns about file system changes through
 * explicit refreshes or file watcher events, and events are never delivered for changes made while the server was
 * down. Without this step new source files simply do not exist for the compiler and everything referencing them turns
 * red.</li>
 * <li>Compare the BUILD file of every package project with the digest recorded when its classpath was computed (see
 * {@link BuildFileDigestStore}). Packages with a changed BUILD file are re-provisioned (targets, source folders) and
 * their classpath and the classpath of all projects depending on them is re-computed. Packages without changes are
 * left alone, so no Bazel invocation happens at all if only sources changed.</li>
 * <li>Detect changes the incremental path cannot handle (changed project view, removed packages, new packages with
 * Java sources) and report that a full synchronization is required.</li>
 * </ol>
 */
public final class RefreshProjectsJob extends WorkspaceJob {

    /**
     * Summary of what a run did.
     *
     * @param refreshedProjects
     *            number of projects refreshed from disk
     * @param reprovisionedProjects
     *            names of the projects whose BUILD file changed and which were re-provisioned
     * @param classpathsRefreshed
     *            number of projects which got their classpath re-computed (re-provisioned ones plus dependents)
     * @param fullSyncRequired
     *            <code>true</code> if changes were found which require a full synchronization
     * @param messages
     *            human readable details (mostly why a full synchronization is required)
     */
    public record Outcome(
            int refreshedProjects,
            List<String> reprovisionedProjects,
            int classpathsRefreshed,
            boolean fullSyncRequired,
            List<String> messages) {
    }

    private static Logger LOG = LoggerFactory.getLogger(RefreshProjectsJob.class);

    private final BazelWorkspace workspace;
    private final Collection<BazelProject> scope;
    private final boolean checkBuildFiles;

    private volatile Outcome outcome;

    /**
     * @param workspace
     *            the workspace to refresh
     * @param scope
     *            the projects to refresh (<code>null</code> for all projects of the workspace)
     * @param checkBuildFiles
     *            <code>true</code> to also re-provision packages with changed BUILD files and refresh classpaths;
     *            <code>false</code> to only refresh resources from disk
     */
    public RefreshProjectsJob(BazelWorkspace workspace, Collection<BazelProject> scope, boolean checkBuildFiles) {
        super(format("Refreshing Bazel projects of %s", workspace.getLocation().lastSegment()));
        this.workspace = workspace;
        this.scope = scope;
        this.checkBuildFiles = checkBuildFiles;
        setPriority(Job.BUILD);
        setRule(ResourcesPlugin.getWorkspace().getRoot());
    }

    private void addDependents(Collection<BazelProject> allProjects, Set<BazelProject> toRefresh,
            BazelClasspathManager classpathManager) throws CoreException {
        // build reverse dependency map from the saved containers (workspace projects reference each other as projects)
        Map<String, List<BazelProject>> dependentsByProjectName = new HashMap<>();
        for (BazelProject project : allProjects) {
            if (project.isWorkspaceProject()) {
                continue;
            }
            var container = classpathManager.getSavedContainer(project.getProject());
            if (container == null) {
                continue;
            }
            for (IClasspathEntry entry : container.getClasspathEntries()) {
                if (entry.getEntryKind() == IClasspathEntry.CPE_PROJECT) {
                    dependentsByProjectName.computeIfAbsent(entry.getPath().lastSegment(), k -> new ArrayList<>())
                            .add(project);
                }
            }
        }

        Deque<BazelProject> queue = new ArrayDeque<>(toRefresh);
        while (!queue.isEmpty()) {
            var project = queue.poll();
            for (BazelProject dependent : dependentsByProjectName.getOrDefault(project.getName(), List.of())) {
                if (toRefresh.add(dependent)) {
                    queue.add(dependent);
                }
            }
        }
    }

    @Override
    public boolean belongsTo(Object family) {
        return BazelCoreSharedContstants.PLUGIN_ID.equals(family);
    }

    /**
     * Scheduling rule which needs to be acquired for calling {@link #runInWorkspace(IProgressMonitor)} directly from
     * within another job.
     *
     * @return the required scheduling rule (maybe <code>null</code> in case none is missing)
     */
    public ISchedulingRule detectMissingRule() {
        var requiredRule = getRule();
        var currentRule = getJobManager().currentRule();
        if ((currentRule != null) && !currentRule.contains(requiredRule)) {
            return requiredRule;
        }
        return null;
    }

    /**
     * Finds packages within the project view directories which have Java sources but no project in the workspace.
     */
    private List<String> findPackagesWithoutProject(Collection<BazelProject> projects) throws CoreException, IOException {
        var projectView = workspace.getBazelProjectView();
        var workspaceRoot = workspace.getLocation().toPath();

        Set<Path> knownPackages = new HashSet<>();
        for (BazelProject project : projects) {
            var packageLocation = BuildFileDigestStore.getPackageLocation(project);
            if (packageLocation != null) {
                knownPackages.add(packageLocation);
            }
        }

        Set<Path> excludedDirectories = new HashSet<>();
        for (WorkspacePath excluded : projectView.directoriesToExclude()) {
            excludedDirectories.add(workspaceRoot.resolve(excluded.relativePath()));
        }

        List<String> result = new ArrayList<>();
        for (WorkspacePath directory : projectView.directoriesToImport()) {
            var start = workspaceRoot.resolve(directory.relativePath());
            if (!Files.isDirectory(start)) {
                continue;
            }
            // note: the walk does not follow symbolic links, so bazel-* output links are never entered
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                private final Deque<Path> packagesWithoutJava = new ArrayDeque<>();
                private final Set<Path> packagesWithJava = new HashSet<>();

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (!packagesWithoutJava.isEmpty() && packagesWithoutJava.peek().equals(dir)) {
                        packagesWithoutJava.pop();
                        if (packagesWithJava.remove(dir) && !knownPackages.contains(dir)) {
                            result.add(workspaceRoot.relativize(dir).toString());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    var name = dir.getFileName().toString();
                    if (name.startsWith(".") || excludedDirectories.contains(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (Files.isRegularFile(dir.resolve(BazelCoreSharedContstants.FILE_NAME_BUILD_BAZEL))
                            || Files.isRegularFile(dir.resolve(BazelCoreSharedContstants.FILE_NAME_BUILD))) {
                        packagesWithoutJava.push(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!packagesWithoutJava.isEmpty() && file.getFileName().toString().endsWith(".java")) {
                        packagesWithJava.add(packagesWithoutJava.peek());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return result;
    }

    /**
     * Collects the projects of the workspace directly from the Eclipse workspace.
     * <p>
     * Deliberately not using {@link BazelWorkspace#getBazelProjects()}: that loads the workspace info, which runs
     * <code>bazel info</code>. The refresh must not start a Bazel server when nothing changed (eg., on every start of
     * the language server).
     * </p>
     */
    private List<BazelProject> findBazelProjects() throws CoreException {
        var workspaceRoot = workspace.getLocation();
        var modelManager = workspace.getModel().getModelManager();
        List<BazelProject> result = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.isOpen() && project.hasNature(BazelCoreSharedContstants.BAZEL_NATURE_ID)
                    && BazelProject.hasWorkspaceRootPropertySetToLocation(project, workspaceRoot)) {
                result.add(modelManager.getBazelProject(project));
            }
        }
        return result;
    }

    /**
     * @return <code>true</code> if any of the files was modified after the given time, or the time is unknown
     *         (<code>0</code>)
     */
    private static boolean isNewerThan(List<Path> files, long timestamp) throws IOException {
        if (timestamp <= 0) {
            return true;
        }
        for (Path file : files) {
            if (Files.isRegularFile(file) && (Files.getLastModifiedTime(file).toMillis() > timestamp)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the outcome of the last run (<code>null</code> if the job did not run or failed)
     */
    public Outcome getOutcome() {
        return outcome;
    }

    private int refreshResources(Collection<BazelProject> projects, TracingSubMonitor monitor) throws CoreException {
        monitor.setWorkRemaining(projects.size());
        var refreshed = 0;
        for (BazelProject bazelProject : projects) {
            monitor.checkCanceled();
            var project = bazelProject.getProject();
            if (!project.isAccessible()) {
                monitor.worked(1);
                continue;
            }
            monitor.subTask(project.getName());
            if (bazelProject.isWorkspaceProject()) {
                // the workspace project sits at the repository root; folders outside the project view are hidden
                // and never crawled, so we only refresh the top level and the project view folder
                project.refreshLocal(DEPTH_ONE, monitor.slice(1));
                var projectViewFolder = workspace.getBazelProjectFileSystemMapper()
                        .getProjectViewLocation()
                        .removeLastSegments(1)
                        .makeRelativeTo(project.getLocation());
                if (!projectViewFolder.isEmpty() && !projectViewFolder.isAbsolute()) {
                    var folder = project.getFolder(projectViewFolder);
                    if (folder.exists()) {
                        folder.refreshLocal(DEPTH_INFINITE, monitor.slice(1));
                    }
                }
            } else {
                project.refreshLocal(DEPTH_INFINITE, monitor.slice(1));
            }
            refreshed++;
        }
        return refreshed;
    }

    private List<BazelProject> reprovision(List<BazelProject> changedProjects, List<String> messages,
            TracingSubMonitor monitor) throws CoreException {
        monitor.setWorkRemaining(2);
        var projectView = workspace.getBazelProjectView();
        var lookup = new TargetDiscoveryAndProvisioningExtensionLookup();
        var discoveryStrategy = lookup.createTargetDiscoveryStrategy(projectView);
        var provisioningStrategy = lookup.createTargetProvisioningStrategy(projectView);
        var importRoots = SynchronizeProjectViewJob.createImportRoots(workspace);

        List<WorkspacePath> packages = new ArrayList<>();
        for (BazelProject project : changedProjects) {
            var packageLocation = BuildFileDigestStore.getPackageLocation(project);
            if (packageLocation == null) {
                continue;
            }
            packages.add(new WorkspacePath(workspace.getLocation().toPath().relativize(packageLocation).toString()));
        }
        if (packages.isEmpty()) {
            return List.of();
        }

        var discoveredTargets =
                discoveryStrategy.discoverTargets(workspace, packages, monitor.split(1, "Discovering targets"));
        Set<TargetExpression> targets = new LinkedHashSet<>();
        for (TargetExpression target : discoveredTargets) {
            if ((target instanceof Label label) && !importRoots.targetInProject(label)) {
                continue;
            }
            targets.add(target);
        }
        if (targets.isEmpty()) {
            messages.add(
                format(
                    "No targets left in package(s) %s; a full synchronization is required to remove the project(s).",
                    packages.stream().map(WorkspacePath::relativePath).collect(joining(", "))));
            return List.of();
        }

        var provisioned = provisioningStrategy
                .provisionProjectsForSelectedTargets(targets, workspace, monitor.split(1, "Provisioning projects"));
        for (BazelProject project : changedProjects) {
            if (!provisioned.contains(project)) {
                messages.add(
                    format(
                        "Project '%s' has no supported targets anymore; a full synchronization is required to remove it.",
                        project.getName()));
            }
        }
        return provisioned;
    }

    @Override
    public IStatus runInWorkspace(IProgressMonitor progress) throws CoreException {
        var monitor = TracingSubMonitor.convert(progress, getName(), 100);
        var messages = new ArrayList<String>();
        var fullSyncRequired = false;
        var invalidationSuspended = false;
        var modelManager = workspace.getModel().getModelManager();
        try {
            var projects = scope != null ? new ArrayList<>(scope) : findBazelProjects();

            // 1. resources
            var refreshed = refreshResources(projects, monitor.split(30, "Refreshing resources"));
            LOG.info("Refreshed {} Bazel project(s) of workspace '{}' from disk", refreshed, workspace.getName());

            if (!checkBuildFiles) {
                outcome = new Outcome(refreshed, List.of(), 0, false, messages);
                return Status.OK_STATUS;
            }

            // the model caches may hold information read from the old BUILD files
            workspace.getModel().getInfoCache().invalidateAll();
            modelManager.getResourceChangeProcessor().suspendInvalidationFor(workspace);
            invalidationSuspended = true;

            var classpathManager = modelManager.getClasspathManager();
            var digestStore = classpathManager.getBuildFileDigestStore();

            // 2. project view (the workspace project is looked up without loading the workspace info, see above)
            BazelProject workspaceProject = null;
            for (BazelProject project : findBazelProjects()) {
                if (project.isWorkspaceProject()) {
                    workspaceProject = project;
                    break;
                }
            }
            if (workspaceProject == null) {
                workspaceProject = workspace.getBazelProject();
            }
            var projectViewDigest = BuildFileDigestStore.computeDigest(workspaceProject);
            var recordedProjectViewDigest = digestStore.get(workspaceProject.getName());
            if (recordedProjectViewDigest == null) {
                // nothing recorded yet: the saved classpaths are only known to match the project view when
                // none of its files was modified after the classpaths were saved
                var containerTimestamp = classpathManager.getSavedContainerTimestamp(workspaceProject.getProject());
                if (isNewerThan(BuildFileDigestStore.getProjectViewFiles(workspace), containerTimestamp)) {
                    messages.add(
                        "The project view (.bazelproject) is newer than the saved classpaths; a full synchronization is required.");
                    outcome = new Outcome(refreshed, List.of(), 0, true, messages);
                    return Status.warning(messages.get(0));
                }
                digestStore.put(workspaceProject.getName(), projectViewDigest);
            } else if (!recordedProjectViewDigest.equals(projectViewDigest)) {
                messages.add("The project view (.bazelproject) changed; a full synchronization is required.");
                outcome = new Outcome(refreshed, List.of(), 0, true, messages);
                return Status.warning(messages.get(0));
            }

            // 3. BUILD files
            List<BazelProject> changedProjects = new ArrayList<>();
            for (BazelProject project : projects) {
                if (project.isWorkspaceProject()) {
                    continue;
                }
                var buildFile = BuildFileDigestStore.findBuildFile(project);
                var digest = buildFile != null ? BuildFileDigestStore.computeDigest(List.of(buildFile)) : null;
                if (digest == null) {
                    messages.add(
                        format(
                            "The BUILD file of project '%s' is gone; a full synchronization is required to remove the project.",
                            project.getName()));
                    fullSyncRequired = true;
                    continue;
                }
                var recorded = digestStore.get(project.getName());
                if (recorded == null) {
                    // nothing recorded yet (first run after upgrade): the saved classpath was computed from the BUILD
                    // file only if the file was not modified after the classpath was saved (git sets the
                    // modification time when a file enters the working tree)
                    var containerTimestamp = classpathManager.getSavedContainerTimestamp(project.getProject());
                    if (isNewerThan(List.of(buildFile), containerTimestamp)) {
                        LOG.info(
                            "No build file digest recorded for '{}' and its BUILD file is newer than the saved classpath. Treating it as changed.",
                            project.getName());
                        changedProjects.add(project);
                    } else {
                        digestStore.put(project.getName(), digest);
                    }
                } else if (!recorded.equals(digest)) {
                    changedProjects.add(project);
                }
            }
            monitor.worked(5);

            // 4. new packages (only meaningful when looking at the whole workspace)
            if (scope == null) {
                var newPackages = findPackagesWithoutProject(projects);
                if (!newPackages.isEmpty()) {
                    messages.add(
                        format(
                            "New package(s) with Java sources found: %s; a full synchronization is required to import them.",
                            String.join(", ", newPackages)));
                    fullSyncRequired = true;
                }
            }
            monitor.worked(5);

            // 5. re-provision changed packages and refresh classpaths
            Set<BazelProject> toRefresh = new LinkedHashSet<>();
            List<String> reprovisionedNames = new ArrayList<>();
            if (!changedProjects.isEmpty()) {
                LOG.info(
                    "BUILD file changed for {} project(s): {}",
                    changedProjects.size(),
                    changedProjects.stream().map(BazelProject::getName).collect(joining(", ")));
                var reprovisioned =
                        reprovision(changedProjects, messages, monitor.split(20, "Re-provisioning changed packages"));
                reprovisioned.forEach(p -> reprovisionedNames.add(p.getName()));
                toRefresh.addAll(reprovisioned);
                addDependents(projects, toRefresh, classpathManager);
                if (!toRefresh.isEmpty()) {
                    var status = new InitializeOrRefreshClasspathJob(toRefresh.stream(), classpathManager, true)
                            .runInWorkspace(monitor.split(40, "Refreshing classpaths"));
                    if (status.matches(IStatus.ERROR)) {
                        throw new CoreException(status);
                    }
                }
            }

            fullSyncRequired |= !messages.isEmpty();
            outcome = new Outcome(refreshed, reprovisionedNames, toRefresh.size(), fullSyncRequired, messages);
            LOG.info(
                "Refresh of workspace '{}' done: {} project(s) refreshed, {} re-provisioned, {} classpath(s) updated{}",
                workspace.getName(),
                refreshed,
                reprovisionedNames.size(),
                toRefresh.size(),
                fullSyncRequired ? "; full synchronization required: " + String.join(" ", messages) : "");
            return fullSyncRequired ? Status.warning(String.join(" ", messages)) : Status.OK_STATUS;
        } catch (OperationCanceledException e) {
            LOG.warn("Refresh of workspace '{}' cancelled", workspace.getLocation());
            return Status.CANCEL_STATUS;
        } catch (IOException e) {
            LOG.error("Error refreshing workspace '{}': {}", workspace.getLocation(), e.getMessage(), e);
            return Status.error(format("Error refreshing workspace '%s': %s", workspace.getLocation(), e.getMessage()), e);
        } finally {
            if (invalidationSuspended) {
                modelManager.getResourceChangeProcessor().resumeInvalidationFor(workspace);
            }
            IProgressMonitor.done(progress);
        }
    }
}
