package com.appsmith.server.solutions.ce;

import com.appsmith.external.constants.AnalyticsEvents;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.User;
import com.appsmith.server.domains.Workspace;
import com.appsmith.server.dtos.ApplicationImportDTO;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.ResponseUtils;
import com.appsmith.server.services.AnalyticsService;
import com.appsmith.server.services.ApplicationService;
import com.appsmith.server.services.SessionUserService;
import com.appsmith.server.services.WorkspaceService;
import com.appsmith.server.solutions.ApplicationPermission;
import com.appsmith.server.solutions.ForkExamplesWorkspace;
import com.appsmith.server.solutions.ImportExportApplicationService;
import com.appsmith.server.solutions.WorkspacePermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
@Slf4j
public class ApplicationForkingServiceCEImpl implements ApplicationForkingServiceCE {

    private final ApplicationService applicationService;
    private final WorkspaceService workspaceService;
    private final ForkExamplesWorkspace forkExamplesWorkspace;
    private final SessionUserService sessionUserService;
    private final AnalyticsService analyticsService;
    private final ResponseUtils responseUtils;
    private final WorkspacePermission workspacePermission;
    private final ApplicationPermission applicationPermission;
    private final ImportExportApplicationService importExportApplicationService;

    @Override
    public Mono<Application> forkApplicationToWorkspaceWithEnvironment(
            String srcApplicationId, String targetWorkspaceId, String sourceEnvironmentId) {
        final Mono<Application> sourceApplicationMono = applicationService
                .findById(srcApplicationId, applicationPermission.getReadPermission())
                .switchIfEmpty(Mono.error(new AppsmithException(
                        AppsmithError.NO_RESOURCE_FOUND, FieldName.APPLICATION, srcApplicationId)));

        final Mono<Workspace> targetWorkspaceMono = workspaceService
                .findById(targetWorkspaceId, workspacePermission.getApplicationCreatePermission())
                .switchIfEmpty(Mono.error(new AppsmithException(
                        AppsmithError.NO_RESOURCE_FOUND, FieldName.WORKSPACE, targetWorkspaceId)));

        Mono<User> userMono = sessionUserService.getCurrentUser();

        // For collecting all the possible event data
        Map<String, Object> eventData = new HashMap<>();

        Mono<Application> forkApplicationMono = Mono.zip(sourceApplicationMono, targetWorkspaceMono, userMono)
                .flatMap(tuple -> {
                    final Application application = tuple.getT1();
                    final Workspace targetWorkspace = tuple.getT2();
                    final User user = tuple.getT3();

                    eventData.put(FieldName.WORKSPACE, targetWorkspace);

                    // If the forking application is connected to git, do not copy those data to the new forked
                    // application
                    application.setGitApplicationMetadata(null);

                    boolean allowFork = (
                            // Is this a non-anonymous user that has access to this application?
                            !user.isAnonymous()
                                    && application
                                            .getUserPermissions()
                                            .contains(applicationPermission
                                                    .getEditPermission()
                                                    .getValue()))
                            || Boolean.TRUE.equals(application.getForkingEnabled());

                    if (!allowFork) {
                        return Mono.error(new AppsmithException(AppsmithError.APPLICATION_FORKING_NOT_ALLOWED));
                    }

                    return forkExamplesWorkspace.forkApplications(
                            targetWorkspace.getId(),
                            Flux.fromIterable(Collections.singletonList(application)),
                            sourceEnvironmentId);
                })
                .flatMap(applicationIds -> {
                    final String newApplicationId = applicationIds.get(0);
                    return applicationService
                            .getById(newApplicationId)
                            .flatMap(application -> sendForkApplicationAnalyticsEvent(
                                    srcApplicationId, targetWorkspaceId, application, eventData));
                });

        // Fork application is currently a slow API because it needs to create application, clone all the pages, and
        // then
        // copy all the actions and collections. This process may take time and the client may cancel the request.
        // This leads to the flow getting stopped midway producing corrupted DB objects. The following ensures that even
        // though the client may have cancelled the flow, the forking of the application should proceed uninterrupted
        // and whenever the user refreshes the page, the sane forked application is available.
        // To achieve this, we use a synchronous sink which does not take subscription cancellations into account. This
        // means that even if the subscriber has cancelled its subscription, the create method still generates its
        // event.
        return Mono.create(
                sink -> forkApplicationMono.subscribe(sink::success, sink::error, null, sink.currentContext()));
    }

    public Mono<ApplicationImportDTO> forkApplicationToWorkspace(
            String srcApplicationId, String targetWorkspaceId, String branchName) {

        // First we try to find the correct database entry of application to fork, based on git
        Mono<Application> applicationMono;

        if (StringUtils.isEmpty(branchName)) {
            applicationMono = applicationService
                    .findById(srcApplicationId, applicationPermission.getReadPermission())
                    .switchIfEmpty(Mono.error(new AppsmithException(
                            AppsmithError.NO_RESOURCE_FOUND, FieldName.APPLICATION, srcApplicationId)))
                    .flatMap(application -> {
                        // For git connected application user can update the default branch
                        // In such cases we should fork the application from the new default branch
                        if (!(application.getGitApplicationMetadata() == null)
                                && !application
                                        .getGitApplicationMetadata()
                                        .getBranchName()
                                        .equals(application
                                                .getGitApplicationMetadata()
                                                .getDefaultBranchName())) {
                            return applicationService.findByBranchNameAndDefaultApplicationId(
                                    application.getGitApplicationMetadata().getDefaultBranchName(),
                                    srcApplicationId,
                                    applicationPermission.getReadPermission());
                        }
                        return Mono.just(application);
                    });
        } else {
            applicationMono = applicationService.findByBranchNameAndDefaultApplicationId(
                    branchName, srcApplicationId, applicationPermission.getReadPermission());
        }

        return applicationMono
                // We will be forking to the default environment in the new workspace
                .zipWhen(application -> workspaceService.getDefaultEnvironmentId(application.getWorkspaceId(), null))
                .flatMap(tuple -> {
                    String fromApplicationId = tuple.getT1().getId();
                    String sourceEnvironmentId = tuple.getT2();
                    return forkApplicationToWorkspaceWithEnvironment(
                                    fromApplicationId, targetWorkspaceId, sourceEnvironmentId)
                            .map(responseUtils::updateApplicationWithDefaultResources)
                            .flatMap(application -> importExportApplicationService.getApplicationImportDTO(
                                    application.getId(), application.getWorkspaceId(), application));
                });
    }

    private Mono<Application> sendForkApplicationAnalyticsEvent(
            String applicationId, String workspaceId, Application application, Map<String, Object> eventData) {
        return applicationService
                .findById(applicationId, applicationPermission.getReadPermission())
                .flatMap(sourceApplication -> {
                    final Map<String, Object> data = Map.of(
                            "forkedFromAppId",
                            applicationId,
                            "forkedToOrgId",
                            workspaceId,
                            "forkedFromAppName",
                            sourceApplication.getName(),
                            FieldName.EVENT_DATA,
                            eventData);

                    return analyticsService.sendObjectEvent(AnalyticsEvents.FORK, application, data);
                })
                .onErrorResume(e -> {
                    log.warn("Error sending action execution data point", e);
                    return Mono.just(application);
                });
    }
}
