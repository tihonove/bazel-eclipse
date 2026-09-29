/*-
 *
 */
package com.salesforce.bazel.eclipse.jdtls.commands;

import static org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin.logInfo;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jdt.ls.core.internal.IDelegateCommandHandler;

import com.salesforce.bazel.eclipse.core.BazelCore;
import com.salesforce.bazel.eclipse.core.BazelCorePlugin;
import com.salesforce.bazel.eclipse.core.classpath.InitializeOrRefreshClasspathJob;
import com.salesforce.bazel.eclipse.core.model.BazelWorkspace;
import com.salesforce.bazel.eclipse.core.model.RefreshProjectsJob;
import com.salesforce.bazel.eclipse.core.model.SynchronizeProjectViewJob;
import com.salesforce.bazel.eclipse.jdtls.execution.ReconnectingSocket;
import com.salesforce.bazel.eclipse.jdtls.execution.StreamingSocketBazelCommandExecutor;

/**
 * Bazel JDT LS Commands
 */
@SuppressWarnings("restriction")
public class BazelJdtLsDelegateCommandHandler implements IDelegateCommandHandler {

    private static final AtomicReference<ReconnectingSocket> reconnectingSocketRef = new AtomicReference<>();

    @Override
    public Object executeCommand(String commandId, List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (commandId != null) {
            switch (commandId) {
                case "java.bazel.updateClasspaths":
                    var sourceFileUri = (String) arguments.get(0);
                    var containers = ResourcesPlugin.getWorkspace()
                            .getRoot()
                            .findContainersForLocationURI(new URI(sourceFileUri));
                    Set<IProject> projects = new HashSet<>();
                    for (IContainer container : containers) {
                        projects.add(container.getProject());
                    }
                    new InitializeOrRefreshClasspathJob(
                            projects,
                            BazelCorePlugin.getInstance().getBazelModelManager().getClasspathManager(),
                            true /* force */).schedule();
                    return new Object();
                case "java.bazel.syncProjects":
                    var workspaces = BazelCore.getModel().getBazelWorkspaces();
                    for (BazelWorkspace workspace : workspaces) {
                        new SynchronizeProjectViewJob(workspace).schedule();
                    }
                    return new Object();
                case "java.bazel.refreshProjects":
                    // LOCAL PATCH: incremental refresh (resources from disk, changed BUILD files, dependent classpaths)
                    return refreshProjects(monitor);
                case "java.bazel.connectProcessStreamSocket":
                    var port = 0;
                    var portArg = arguments.get(0);
                    if (portArg instanceof Number) {
                        port = ((Number) portArg).intValue();
                    } else if (portArg instanceof String) {
                        port = Integer.parseInt((String) portArg);
                    }
                    if ((port > 0) && (port < 65535)) {
                        Integer staticPort = port;
                        var reconnectingSocket = new ReconnectingSocket(staticPort);
                        setReconnectingSocket(reconnectingSocket);
                        logInfo("Enabled Bazel command output streaming to port: " + port);
                        return Boolean.TRUE;
                    } else {
                        StreamingSocketBazelCommandExecutor.setLocalPortHostSupplier(null);
                        logInfo("Disabled Bazel command output streaming");
                        return Boolean.FALSE;
                    }
                default:
                    break;
            }
        }
        throw new UnsupportedOperationException(
                String.format("Bazel JDT LS extension doesn't support the command '%s'.", commandId));
    }

    /**
     * LOCAL PATCH: runs {@link RefreshProjectsJob} for every workspace synchronously and reports the outcome.
     *
     * @return a map (serialized as JSON for the client) with <code>refreshedProjects</code>,
     *         <code>reprovisionedProjects</code>, <code>classpathsRefreshed</code>, <code>fullSyncRequired</code> and
     *         <code>messages</code>
     */
    private Map<String, Object> refreshProjects(IProgressMonitor monitor) throws CoreException {
        var refreshedProjects = 0;
        var classpathsRefreshed = 0;
        var fullSyncRequired = false;
        List<String> reprovisionedProjects = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        for (BazelWorkspace workspace : BazelCore.getModel().getBazelWorkspaces()) {
            var job = new RefreshProjectsJob(workspace, null /* all projects */, true /* check BUILD files */);
            var status = new IStatus[1];
            ResourcesPlugin.getWorkspace()
                    .run(
                        progress -> status[0] = job.runInWorkspace(progress),
                        job.detectMissingRule(),
                        IWorkspace.AVOID_UPDATE,
                        monitor);
            if ((status[0] != null) && status[0].matches(IStatus.ERROR)) {
                throw new CoreException(status[0]);
            }
            var outcome = job.getOutcome();
            if (outcome == null) {
                continue;
            }
            refreshedProjects += outcome.refreshedProjects();
            classpathsRefreshed += outcome.classpathsRefreshed();
            fullSyncRequired |= outcome.fullSyncRequired();
            reprovisionedProjects.addAll(outcome.reprovisionedProjects());
            messages.addAll(outcome.messages());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("refreshedProjects", refreshedProjects);
        result.put("reprovisionedProjects", reprovisionedProjects);
        result.put("classpathsRefreshed", classpathsRefreshed);
        result.put("fullSyncRequired", fullSyncRequired);
        result.put("messages", messages);
        return result;
    }

    private void setReconnectingSocket(ReconnectingSocket reconnectingSocket) {
        // switch to new
        StreamingSocketBazelCommandExecutor.setLocalPortHostSupplier(reconnectingSocket);

        // dispose old
        var old = reconnectingSocketRef.getAndSet(reconnectingSocket);
        if (old != null) {
            old.close();
        }
    }

}
