package com.github.lipiridi.spotless.applier;

import com.github.lipiridi.spotless.applier.enums.BuildTool;
import com.intellij.codeInsight.actions.ReformatCodeProcessor;
import com.intellij.execution.Executor;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.impl.DefaultJavaProgramRunner;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Version;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.gradle.util.GradleVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType;
import org.jetbrains.idea.maven.execution.MavenRunner;
import org.jetbrains.idea.maven.execution.MavenRunnerParameters;
import org.jetbrains.idea.maven.execution.MavenRunnerSettings;
import org.jetbrains.idea.maven.utils.MavenUtil;
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings;
import org.jetbrains.plugins.gradle.settings.GradleSettings;
import org.jetbrains.plugins.gradle.util.GradleConstants;

public class ReformatProcessor {

    private static final Logger LOG = Logger.getInstance(ReformatCodeProcessor.class);
    private static final Version NO_CONFIG_CACHE_MIN_GRADLE_VERSION = new Version(6, 6, 0);
    private static final NotificationGroup NOTIFICATION_GROUP =
            NotificationGroupManager.getInstance().getNotificationGroup("Spotless Applier");
    private final Project project;
    private final String projectBasePath;
    private final ReformatTaskCallback reformatTaskCallback;
    private final Document document;
    private final PsiFile psiFile;
    private final boolean reformatAllFiles;

    public ReformatProcessor(@NotNull Project project) {
        this(project, null, null);
    }

    public ReformatProcessor(@NotNull Project project, Document document, PsiFile psiFile) {
        this.project = project;
        this.projectBasePath = project.getBasePath();
        this.document = document;
        this.psiFile = psiFile;
        this.reformatAllFiles = document == null;
        this.reformatTaskCallback = new ReformatTaskCallback(project, NOTIFICATION_GROUP, document);
    }

    public void run() {
        if (projectBasePath == null) {
            return;
        }

        VirtualFile baseVirtualFile = VfsUtil.findFile(Path.of(projectBasePath), true);

        if (baseVirtualFile == null) {
            return;
        }

        BuildTool buildTool = resolveBuildTool(baseVirtualFile);

        if (buildTool == null) {
            NOTIFICATION_GROUP
                    .createNotification("Unable to resolve build tool", NotificationType.ERROR)
                    .notify(project);
            return;
        }

        optimizeImports();

        switch (Objects.requireNonNull(buildTool)) {
            case GRADLE -> executeGradleTask();
            case MAVEN -> executeMavenTask();
        }
    }

    private BuildTool resolveBuildTool(VirtualFile file) {
        if (!file.isDirectory()) {
            return null;
        }
        for (VirtualFile child : file.getChildren()) {
            switch (child.getName()) {
                case "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts" -> {
                    return BuildTool.GRADLE;
                }
                case "pom.xml" -> {
                    return BuildTool.MAVEN;
                }
            }
        }
        return null;
    }

    private void executeGradleTask() {
        ExternalSystemTaskExecutionSettings externalSettings = getGradleSystemTaskExecutionSettings();

        ToolEnvExternalSystemUtil.runTask(
                externalSettings,
                DefaultRunExecutor.EXECUTOR_ID,
                project,
                GradleConstants.SYSTEM_ID,
                reformatTaskCallback,
                document);
    }

    @NotNull private ExternalSystemTaskExecutionSettings getGradleSystemTaskExecutionSettings() {
        final String noConfigCacheOption = shouldAddNoConfigCacheOption() ? "--no-configuration-cache" : "";
        String scriptParameters = "";

        ExternalSystemTaskExecutionSettings externalSettings = new ExternalSystemTaskExecutionSettings();
        externalSettings.setExternalProjectPath(projectBasePath);
        externalSettings.setTaskNames(Collections.singletonList("spotlessApply"));
        externalSettings.setExternalSystemIdString(GradleConstants.SYSTEM_ID.getId());
        if (!reformatAllFiles) {
            scriptParameters = String.format(
                    "-PspotlessIdeHook=\"%s\" ", psiFile.getVirtualFile().getPath());
        }

        externalSettings.setScriptParameters(scriptParameters + noConfigCacheOption);

        return externalSettings;
    }

    private boolean shouldAddNoConfigCacheOption() {
        Optional<GradleProjectSettings> maybeProjectSettings =
                Optional.ofNullable(GradleSettings.getInstance(project).getLinkedProjectSettings(projectBasePath));

        if (maybeProjectSettings.isEmpty()) {
            LOG.warn("Unable to parse linked project settings, leaving off `--no-configuration-cache` argument");
            return false;
        }

        GradleVersion gradleVersion = maybeProjectSettings.get().resolveGradleVersion();

        return Objects.requireNonNull(Version.parseVersion(gradleVersion.getVersion()))
                .isOrGreaterThan(NO_CONFIG_CACHE_MIN_GRADLE_VERSION.major, NO_CONFIG_CACHE_MIN_GRADLE_VERSION.minor);
    }

    private void executeMavenTask() {
        List<String> commands = Collections.singletonList("spotless:apply");

        ExternalSystemTaskExecutionSettings externalSettings = new ExternalSystemTaskExecutionSettings();
        externalSettings.setTaskNames(commands);

        ExecutionEnvironment environment = getMavenExecutionEnvironment(commands);

        ToolEnvExternalSystemUtil.runTask(
                externalSettings,
                DefaultRunExecutor.EXECUTOR_ID,
                project,
                MavenUtil.SYSTEM_ID,
                reformatTaskCallback,
                document,
                environment);
    }

    @NotNull private ExecutionEnvironment getMavenExecutionEnvironment(List<String> commands) {
        MavenRunner mavenRunner = MavenRunner.getInstance(project);

        MavenRunnerSettings settings = mavenRunner.getState().clone();

        MavenRunnerParameters params = new MavenRunnerParameters();
        params.setWorkingDirPath(projectBasePath);
        params.setGoals(commands);

        if (!reformatAllFiles) {
            settings.setVmOptions(String.format(
                    "-DspotlessFiles=\"%s\"",
                    psiFile.getVirtualFile()
                            .getPath()
                            .replaceAll("\\.", "\\\\.")
                            .replaceAll("/", ".")));
        }

        RunnerAndConfigurationSettings configSettings = MavenRunConfigurationType.createRunnerAndConfigurationSettings(
                null, settings, params, project, MavenRunConfigurationType.generateName(project, params), false);

        configSettings.setActivateToolWindowBeforeRun(false);

        ProgramRunner<?> runner = DefaultJavaProgramRunner.getInstance();
        Executor executor = DefaultRunExecutor.getRunExecutorInstance();
        return new ExecutionEnvironment(executor, runner, configSettings, project);
    }

    private void optimizeImports() {
        SynchronousOptimizeImportsProcessor synchronousOptimizeImportsProcessor = psiFile == null
                ? new SynchronousOptimizeImportsProcessor(project)
                : new SynchronousOptimizeImportsProcessor(project, psiFile);

        synchronousOptimizeImportsProcessor.run();
    }
}
