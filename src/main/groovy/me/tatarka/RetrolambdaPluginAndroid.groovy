/**
 Copyright 2014 Evan Tatarka

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package me.tatarka
import com.android.build.gradle.api.TestVariant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import proguard.gradle.ProGuardTask

import static me.tatarka.RetrolambdaPlugin.checkIfExecutableExists
/**
 * Created with IntelliJ IDEA.
 * User: evan
 * Date: 8/4/13
 * Time: 1:36 PM
 * To change this template use File | Settings | File Templates.
 */
public class RetrolambdaPluginAndroid implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.afterEvaluate {
            def sdkDir

            Properties properties = new Properties()
            File localProps = project.rootProject.file('local.properties')
            if (localProps.exists()) {
                properties.load(localProps.newDataInputStream())
                sdkDir = properties.getProperty('sdk.dir')
            } else {
                sdkDir = System.getenv('ANDROID_HOME')
            }

            if (!sdkDir) {
                throw new ProjectConfigurationException("Cannot find android sdk. Make sure sdk.dir is defined in local.properties or the environment variable ANDROID_HOME is set.", null)
            }

            def androidJar = "$sdkDir/platforms/$project.android.compileSdkVersion/android.jar"

            def buildPath = "$project.buildDir/retrolambda"
            def jarPath = "$buildPath/$project.android.compileSdkVersion"

            def isLibrary = project.plugins.hasPlugin('android-library')

            def variants = (isLibrary ?
                    project.android.libraryVariants :
                    project.android.applicationVariants) + project.android.testVariants

            variants.each { var ->
                if (project.retrolambda.isIncluded(var.name)) {
                    def name = var.name.capitalize()
                    def oldDestDir = var.javaCompile.destinationDir
                    def newDestDir = project.file("$buildPath/$var.name")
                    def classpathFiles =
                            var.javaCompile.classpath +
                                    project.files("$buildPath/$var.name") +
                                    project.files(androidJar)

                    def newJavaCompile = project.task("_$var.javaCompile.name", dependsOn: ["patchAndroidJar"], type: JavaCompile) {
                        source = var.javaCompile.source
                        classpath = var.javaCompile.classpath
                        destinationDir = newDestDir
                        sourceCompatibility = "1.8"
                        targetCompatibility = "1.8"
                        options.compilerArgs = var.javaCompile.options.compilerArgs + ["-bootclasspath", "$jarPath/android.jar"]
                    }

                    var.javaCompile.dependsOn.each { dependency ->
                        newJavaCompile.dependsOn(dependency)
                    }

                    def retrolambdaTask = project.task("compileRetrolambda${name}", dependsOn: [newJavaCompile],  type: RetrolambdaTask) {
                        inputDir = newDestDir
                        outputDir = oldDestDir
                        classpath = classpathFiles
                        javaVersion = project.retrolambda.javaVersion
                        jvmArgs = project.retrolambda.jvmArgs
                    }

                    var.javaCompile.dependsOn(retrolambdaTask)
                    var.javaCompile.deleteAllActions()

                    def extractTaskName = "extract${var.name.capitalize()}Annotations"
                    def extractTask = project.tasks.findByName(extractTaskName)
                    if (extractTask != null) {
                        extractTask.deleteAllActions()
                        project.logger.warn("$extractTaskName is incomaptible with java 8 sources and has been disabled.")
                    }

                    if (!project.retrolambda.onJava8) {
                        // Set JDK 8 for compiler task
                        newJavaCompile.doFirst {
                            it.options.fork = true
                            def javac = "${project.retrolambda.tryGetJdk()}/bin/javac"
                            if (!checkIfExecutableExists(javac)) throw new ProjectConfigurationException("Cannot find executable: $javac", null)
                            it.options.forkOptions.executable = javac
                        }
                    }
                }
            }

            project.task("patchAndroidJar") {
                def rt = "$project.retrolambda.jdk/jre/lib/rt.jar"
                def classesPath = "$buildPath/classes"
                def jdkPathError = " does not exist, make sure that the environment variable JAVA_HOME or JAVA8_HOME, or the gradle property retrolambda.jdk points to a valid version of java8."

                inputs.dir androidJar
                inputs.dir rt
                outputs.dir jarPath
                outputs.dir classesPath
                
                if (!project.file(androidJar).exists()) {
                    throw new ProjectConfigurationException("Retrolambd: $androidJar does not exsit, make sure ANDROID_HOME or sdk.dir is correctly set to the android sdk directory.", null)
                }

                doLast {
                    project.copy {
                        from project.file(androidJar)
                        into project.file(jarPath)
                    }

                    if (!project.file(rt).exists()) {
                        throw new ProjectConfigurationException("Retrolambda: " + rt + jdkPathError, null)
                    }

                    project.copy {
                        from(project.zipTree(project.file(rt))) {
                            include("java/lang/invoke/**/*.class")
                        }

                        into project.file(classesPath)
                    }

                    if (!project.file(classesPath).isDirectory()) {
                        throw new ProjectConfigurationException("Retrolambda: " + "$buildPath/classes" + jdkPathError, null)
                    }

                    project.ant.jar(update: true, destFile: "$jarPath/android.jar") {
                        fileset(dir: "$buildPath/classes")
                    }
                }
            }
        }
    }
}
