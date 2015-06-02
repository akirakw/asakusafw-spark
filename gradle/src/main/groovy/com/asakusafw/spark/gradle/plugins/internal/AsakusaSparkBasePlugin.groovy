/*
 * Copyright 2011-2015 Asakusa Framework Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.asakusafw.spark.gradle.plugins.internal

import groovy.transform.PackageScope

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * A Gradle sub plug-in for Asakusa on Spark compiler.
 */
class AsakusaSparkBasePlugin implements Plugin<Project> {

    private static final String COMPILER_PROJECT_VERSION = '0.1-SNAPSHOT'

    private static final String SPARK_PROJECT_VERSION = '0.1.0-SNAPSHOT'

    private static final String SPARK_ARTIFACT = 'org.apache.spark:spark-core_2.10:1.3.1'

    private Project project

    private AsakusaSparkBaseExtension extension

    /**
     * Applies this plug-in and returns the extension object for the project.
     * @param project the target project
     * @return the corresponded extension
     */
    static AsakusaSparkBaseExtension get(Project project) {
        project.apply plugin: AsakusaSparkBasePlugin
        return project.plugins.getPlugin(AsakusaSparkBasePlugin).extension
    }

    @Override
    void apply(Project project) {
        this.project = project
        this.extension = project.extensions.create('asakusaSparkBase', AsakusaSparkBaseExtension)
        configureExtension()
    }

    private void configureExtension() {
        extension.compilerProjectVersion = COMPILER_PROJECT_VERSION
        extension.sparkProjectVersion = SPARK_PROJECT_VERSION
        extension.sparkArtifact = SPARK_ARTIFACT
    }

    /**
     * Returns the extension.
     * @return the extension
     */
    AsakusaSparkBaseExtension getExtension() {
        return extension;
    }
}
