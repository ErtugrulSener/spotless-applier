package com.github.lipiridi.spotless.applier.action;

import com.github.lipiridi.spotless.applier.ModuleInfo;
import com.github.lipiridi.spotless.applier.ReformatProcessor;
import com.github.lipiridi.spotless.applier.enums.BuildTool;
import com.github.lipiridi.spotless.applier.ui.SelectModuleDialog;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

public class ReformatProjectAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }

        Map<String, ModuleInfo> availableModules = findAvailableModules(project);

        if (availableModules.isEmpty()) {
            return;
        }

        if (availableModules.size() == 1) {
            ModuleInfo moduleInfo = availableModules.values().iterator().next();
            reformatModule(project, moduleInfo);
        } else {
            showModuleSelectionDialog(availableModules, project);
        }
    }

    private void showModuleSelectionDialog(Map<String, ModuleInfo> availableModules, Project project) {
        // The project could be just a folder that contains gradle/maven modules
        // or also a module with own build tool settings
        ModuleInfo projectModuleInfo = availableModules.values().stream()
                .filter(ModuleInfo::rootModule)
                .findFirst()
                .orElse(null);

        List<String> modulesForDialog = availableModules.entrySet().stream()
                .filter(entry -> !entry.getValue().rootModule())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        boolean isProjectAlsoModule = projectModuleInfo != null;
        SelectModuleDialog moduleDialog = new SelectModuleDialog(project, modulesForDialog, isProjectAlsoModule);

        if (moduleDialog.showAndGet()) {
            if (isProjectAlsoModule && moduleDialog.getApplyOnRootProjectCheckbox()) {
                reformatModule(project, projectModuleInfo);
            } else {
                List<String> selectedModules = moduleDialog.getSelectedModules();
                selectedModules.stream()
                        .map(availableModules::get)
                        .forEach(moduleInfo -> reformatModule(project, moduleInfo));
            }

            moduleDialog.saveModuleSettings();
        }
    }

    private Map<String, ModuleInfo> findAvailableModules(Project project) {
        Module[] modules = ProjectUtil.getModules(project);
        String projectBasePath = project.getBasePath();

        return Arrays.stream(modules)
                .map(module -> buildModuleInfo(project, projectBasePath, module))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        moduleInfo -> moduleInfo.module().getName(),
                        Function.identity(),
                        (existing, replacement) -> existing));
    }

    @SuppressWarnings("DataFlowIssue")
    private ModuleInfo buildModuleInfo(Project project, String projectBasePath, Module module) {
        BuildTool buildTool = BuildTool.resolveBuildTool(module);
        if (buildTool == null) {
            return null;
        }

        String modulePath = buildTool.getModulePath(module);
        if (modulePath == null) {
            return null;
        }

        boolean isRootModule = modulePath.equals(projectBasePath);

        Module rootModule =
                switch (buildTool) {
                    case MAVEN -> module;
                    case GRADLE -> {
                        // Gradle defines main and test folders also as modules,
                        // but we want to find only root module
                        VirtualFile moduleVirtualFile = VfsUtil.findFile(Path.of(modulePath), true);
                        yield ModuleUtil.findModuleForFile(moduleVirtualFile, project);
                    }
                };

        return new ModuleInfo(rootModule, modulePath, buildTool, isRootModule);
    }

    private void reformatModule(Project project, ModuleInfo moduleInfo) {
        new ReformatProcessor(project, moduleInfo).run();
    }
}
