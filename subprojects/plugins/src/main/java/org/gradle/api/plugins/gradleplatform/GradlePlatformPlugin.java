/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.plugins.gradleplatform;

import org.gradle.api.Incubating;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.Usage;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.SoftwareComponentFactory;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.gradleplatform.internal.DefaultGradlePlatformExtension;
import org.gradle.api.plugins.gradleplatform.internal.GradlePlatformExtensionInternal;
import org.gradle.api.plugins.gradleplatform.internal.TomlFileGenerator;
import org.gradle.api.plugins.internal.JavaConfigurationVariantMapping;
import org.gradle.api.tasks.TaskProvider;

import javax.inject.Inject;

/**
 * <p>A {@link Plugin} makes it possible to generate a "Gradle platform", which is a set of recommendations
 * for dependency and plugin versions</p>
 *
 * @since 6.8
 */
@Incubating
public class GradlePlatformPlugin implements Plugin<Project> {
    private final static Logger LOGGER = Logging.getLogger(GradlePlatformPlugin.class);

    public static final String GENERATE_PLATFORM_FILE_TASKNAME = "generatePlatformToml";
    public static final String GRADLE_PLATFORM_DEPENDENCIES = "gradlePlatform";
    public static final String GRADLE_PLATFORM_ELEMENTS = "gradlePlatformElements";

    private final SoftwareComponentFactory softwareComponentFactory;

    @Inject
    public GradlePlatformPlugin(SoftwareComponentFactory softwareComponentFactory) {
        this.softwareComponentFactory = softwareComponentFactory;
    }

    @Override
    public void apply(Project project) {
        Configuration dependenciesConfiguration = createDependenciesConfiguration(project);
        GradlePlatformExtensionInternal extension = createExtension(project, dependenciesConfiguration);
        TaskProvider<TomlFileGenerator> generator = createGenerator(project, extension);
        createPublication(project, generator);
    }

    private void createPublication(Project project, TaskProvider<TomlFileGenerator> generator) {
        Configuration exported = project.getConfigurations().create(GRADLE_PLATFORM_ELEMENTS, cnf -> {
            cnf.setDescription("Artifacts for the Gradle platform");
            cnf.setCanBeConsumed(true);
            cnf.setCanBeResolved(false);
            cnf.getOutgoing().artifact(generator);
            cnf.attributes(attrs -> {
                attrs.attribute(Category.CATEGORY_ATTRIBUTE, project.getObjects().named(Category.class, Category.REGULAR_PLATFORM));
                attrs.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, Usage.GRADLE_RECOMMENDATIONS));
            });
        });
        AdhocComponentWithVariants gradlePlatform = softwareComponentFactory.adhoc("gradlePlatform");
        project.getComponents().add(gradlePlatform);
        gradlePlatform.addVariantsFromConfiguration(exported, new JavaConfigurationVariantMapping("compile", true));
    }

    private Configuration createDependenciesConfiguration(Project project) {
        return project.getConfigurations().create(GRADLE_PLATFORM_DEPENDENCIES, cnf -> {
            cnf.setVisible(false);
            cnf.setCanBeConsumed(false);
            cnf.setCanBeResolved(false);
        });
    }

    private TaskProvider<TomlFileGenerator> createGenerator(Project project, GradlePlatformExtensionInternal extension) {
        return project.getTasks().register(GENERATE_PLATFORM_FILE_TASKNAME, TomlFileGenerator.class, t -> configureTask(project, extension, t));
    }

    private void configureTask(Project project, GradlePlatformExtensionInternal extension, TomlFileGenerator task) {
        task.setGroup(BasePlugin.BUILD_GROUP);
        task.setDescription("Generates a TOML file for a Gradle platform");
        task.getOutputFile().convention(project.getLayout().getBuildDirectory().file("gradle-platform/dependencies.toml"));
        task.getDependenciesModel().convention(extension.getDependenciesModel());
        task.getPluginVersions().convention(extension.getPluginVersions());
    }

    private GradlePlatformExtensionInternal createExtension(Project project, Configuration dependenciesConfiguration) {
        return (GradlePlatformExtensionInternal) project.getExtensions()
            .create(GradlePlatformExtension.class, "gradlePlatform", DefaultGradlePlatformExtension.class, dependenciesConfiguration);
    }

}
