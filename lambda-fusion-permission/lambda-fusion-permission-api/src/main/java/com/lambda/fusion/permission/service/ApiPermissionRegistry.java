package com.lambda.fusion.permission.service;

import com.lambda.fusion.permission.model.ApiPermissionMetadata;
import com.lambda.fusion.permission.model.PermissionFileMetadata;
import com.lambda.fusion.permission.model.PermissionPushRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class ApiPermissionRegistry {
    private final List<ApiPermissionProvider> providers;

    public ApiPermissionRegistry(List<ApiPermissionProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    private final AtomicReference<List<ApiPermissionMetadata>> localApis = new AtomicReference<>(List.of());

    private final Map<String, List<ApiPermissionMetadata>> reportedApis = new ConcurrentHashMap<>();

    public void replaceLocal(List<PermissionFileMetadata> files) {
        replaceLocal(files, null);
    }

    public void replaceLocal(List<PermissionFileMetadata> files, String application) {
        List<PermissionFileMetadata> safeFiles = files == null ? List.of() : List.copyOf(files);
        localApis.set(mergeFiles(safeFiles, application));
    }

    private void checkAndMerged(List<ApiPermissionMetadata> merged, PermissionFileMetadata file, String application) {
        if (file == null || file.getApis() == null) {
            return;
        }
        for (ApiPermissionMetadata api : file.getApis()) {
            if (api == null) {
                continue;
            }
            if (api.getApplication() == null || api.getApplication().isBlank()) {
                api.setApplication(application);
            }
            if (api.getModule() == null || api.getModule().isBlank()) {
                api.setModule(file.getModule());
            }
            merged.add(api);
        }
    }

    public List<ApiPermissionMetadata> getLocalApis() {
        List<ApiPermissionMetadata> result = new ArrayList<>(localApis.get());
        providers.forEach(provider -> result.addAll(provider.getApis()));
        return List.copyOf(result);
    }

    public void updateReport(PermissionPushRequest request) {
        if (request == null
                || request.getApplication() == null
                || request.getApplication().isBlank()) {
            return;
        }
        reportedApis.put(request.getApplication(), mergeFiles(request.getFiles(), request.getApplication()));
    }

    public List<ApiPermissionMetadata> getReportedApis(String application) {
        if (application == null || application.isBlank()) {
            return List.of();
        }
        List<ApiPermissionMetadata> reported = reportedApis.get(application);
        List<ApiPermissionMetadata> result = new ArrayList<>(
                reported != null
                        ? reported
                        : localApis.get().stream()
                                .filter(api -> application.equals(api.getApplication()))
                                .toList());
        providers.forEach(provider -> provider.getApis().stream()
                .filter(api -> application.equals(api.getApplication()))
                .forEach(result::add));
        return List.copyOf(result);
    }

    public List<String> getApplications() {
        LinkedHashSet<String> applications = new LinkedHashSet<>(reportedApis.keySet());
        getLocalApis().stream()
                .map(ApiPermissionMetadata::getApplication)
                .filter(application -> application != null && !application.isBlank())
                .forEach(applications::add);
        return applications.stream().sorted().collect(Collectors.toList());
    }

    private List<ApiPermissionMetadata> mergeFiles(List<PermissionFileMetadata> files, String application) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<ApiPermissionMetadata> merged = new ArrayList<>();
        for (PermissionFileMetadata file : files) {
            checkAndMerged(merged, file, application);
        }
        return Collections.unmodifiableList(merged);
    }
}
