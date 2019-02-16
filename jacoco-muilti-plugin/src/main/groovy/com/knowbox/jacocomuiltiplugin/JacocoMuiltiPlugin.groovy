package com.knowbox.jacocomuiltiplugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.testing.jacoco.tasks.JacocoMerge
import org.gradle.testing.jacoco.tasks.JacocoReport
import com.android.build.gradle.api.BaseVariant
class JacocoMuiltiPlugin implements Plugin<Project> {
    String[] ignoreProjects = ["security"]
    @Override
    void apply(final Project rootProject) {
        rootProject.subprojects { p ->
            if(ignoreProjects.contains(p.name)){
                return;
            }
            try{
                if(!ignoreProjects.contains(p.name)){
                    p.afterEvaluate {
                        if(!p.extensions.findByName("android")){
                            return;
                        }
                        println "test:${p.name}"
                        p.plugins.apply('jacoco')
                        p.extensions.android {
                            buildTypes {
                                debug {
                                    testCoverageEnabled = true
                                }
                            }
                        }
                        println "debug:${p.name}"
                    }
                }
            } catch (Exception e){
                e.printStackTrace()
            }

            p.afterEvaluate {
                if(p.getPlugins().hasPlugin("com.android.application")) {
                    println "android:${p.name}"
                }
            }

        }
        rootProject.plugins.apply('jacoco')

        rootProject.jacoco {
            toolVersion = '0.8.1'
        }
        rootProject.task("jacocoTestReport", type: JacocoReport) {
            group = "Reporting"
            reports {
                xml.enabled true
                html.enabled true
                csv.enabled false
            }

            executionData rootProject.fileTree("${rootProject.projectDir}/").include("**/*.ec")
            def classExcludes = ['**/R*.class',
                                 '**/*Factory*.class',
                                 '**/*$InjectAdapter*.class',
                                 '**/*$ModuleAdapter*.class',
                                 '**/*$ViewInjector*.class']

            sourceDirectories = rootProject.files()
            classDirectories = rootProject.files()

            rootProject.allprojects.each {

                sourceDirectories += it.files(it.projectDir.absolutePath + '/src/main/java')
                def path = it.buildDir.absolutePath + '/intermediates/classes/debug'
                classDirectories += it.fileTree(dir: path, excludes: classExcludes, includes: ['**/*.class'])
            }

            doFirst {
                rootProject.fileTree(dir: rootProject.rootDir.absolutePath, includes: ['**/classes/**/*.class']).each { File file ->
                    if (file.name.contains('$$')) {
                        file.renameTo(file.path.replace('$$', '$'))
                    }
                }
            }
        }
    }

    protected static boolean addJacoco(final Project subProject, final JunitJacocoExtension extension) {
        return addJacoco(subProject, extension, null, null)
    }

    protected static boolean addJacoco(final Project subProject, final JunitJacocoExtension extension, JacocoMerge mergeTask, JacocoReport mergedReportTask) {
        if (!shouldIgnore(subProject, extension)) {
            if (isAndroidProject(subProject)) {
                return addJacocoAndroid(subProject, extension, mergeTask, mergedReportTask)
            } else if (isJavaProject(subProject)) {
                return addJacocoJava(subProject, extension, mergeTask, mergedReportTask)
            }
        }

        return false
    }

    private static boolean addJacocoJava(final Project subProject, final JunitJacocoExtension extension, JacocoMerge mergeTask, JacocoReport mergedReportTask) {
        subProject.plugins.apply('jacoco')

        subProject.jacoco {
            toolVersion extension.jacocoVersion
        }

        subProject.jacocoTestReport {
            group = 'Reporting'
            description = 'Generate Jacoco coverage reports.'

            reports {
                xml.enabled = true
                csv.enabled = true
                html.enabled = true
            }

            classDirectories = subProject.fileTree(
                    dir: subProject.buildDir,
                    includes: ['**/classes/**/main/**'],
                    excludes: getExcludes(extension)
            )

            final def coverageSourceDirs = [
                    'src/main/groovy',
                    'src/main/java'
            ]

            additionalSourceDirs = subProject.files(coverageSourceDirs)
            sourceDirectories = subProject.files(coverageSourceDirs)
//            executionData = subProject.files("${subProject.buildDir}/jacoco/test.exec")

//            if (mergeTask != null) {
//                mergeTask.executionData += executionData
//            }
            if (mergedReportTask != null) {
                mergedReportTask.classDirectories += classDirectories
                mergedReportTask.additionalSourceDirs += additionalSourceDirs
                mergedReportTask.sourceDirectories += sourceDirectories
            }
        }

        return true
    }

    private static boolean addJacocoAndroid(final Project subProject, final JunitJacocoExtension extension, JacocoMerge mergeTask, JacocoReport mergedReportTask) {
        subProject.plugins.apply('jacoco')

        subProject.jacoco {
            toolVersion extension.jacocoVersion
        }

        Collection<BaseVariant> variants = []
        if (isAndroidApplication(subProject)) {
            variants = subProject.android.applicationVariants
        } else if (isAndroidLibrary(subProject) || isAndroidFeature(subProject)) {
            // FeatureExtension extends LibraryExtension
            variants = subProject.android.libraryVariants
        } else {
            // test plugin or something else
            return false
        }
        variants.all { variant ->

            def productFlavorName = variant.getFlavorName()
            def buildType = variant.getBuildType()
            def buildTypeName = buildType.name
            def sourceName, sourcePath
            if (!productFlavorName) {
                sourceName = sourcePath = "${buildTypeName}"
            } else {
                sourceName = "${productFlavorName}${buildTypeName.capitalize()}"
                sourcePath = "${productFlavorName}/${buildTypeName}"
            }

            final def combinedTaskName = "combinedTestReport${sourceName.capitalize()}"

            final def instrumentationTestTaskName = "create${sourceName.capitalize()}CoverageReport"
            if (buildType.testCoverageEnabled) {
                addJacocoTask(true, subProject, extension, mergeTask, mergedReportTask, combinedTaskName,
                        "", "", sourceName, sourcePath, productFlavorName, buildTypeName)
            }
        }

        return true
    }

    private static void addJacocoTask(final boolean combined, final Project subProject, final JunitJacocoExtension extension,
                                      JacocoMerge mergeTask, JacocoReport mergedReportTask, final String taskName,
                                      final String jvmTestTaskName, final String instrumentationTestTaskName, final String sourceName,
                                      final String sourcePath, final String productFlavorName, final String buildTypeName) {
        final def destinationDir
        if (combined) {
            destinationDir = "${subProject.buildDir}/reports/jacocoCombined"
        } else {
            destinationDir = "${subProject.buildDir}/reports/jacoco"
        }

        subProject.task(taskName, type: JacocoReport) {
            group = 'Reporting'
            description = "Generate Jacoco coverage reports after collection .ec from ${sourceName} tests."

            reports {
                xml {
                    enabled = true
                    destination subProject.file("$destinationDir/${sourceName}/jacoco.xml")
                }
                csv {
                    enabled = true
                    destination subProject.file("$destinationDir/${sourceName}/jacoco.csv")
                }
                html {
                    enabled = true
                    destination subProject.file("$destinationDir/${sourceName}")
                }
            }

            def classPaths = [
                    "**/intermediates/classes/${sourcePath}/**",
                    "**/intermediates/javac/${sourceName}/*/classes/**" // Android Gradle Plugin 3.2.x support.
            ]

            if (isKotlinAndroid(subProject) || isKotlinMultiplatform(subProject)) {
                classPaths << "**/tmp/kotlin-classes/${sourcePath}/**"
                if (productFlavorName) {
                    classPaths << "**/tmp/kotlin-classes/${productFlavorName}${buildTypeName.capitalize()}/**"
                }
            }

            classDirectories = subProject.fileTree(
                    dir: "${project.buildDir}/intermediates/classes/debug",
                    includes: classPaths,
                    excludes: getExcludes(extension)
            )

            final def coverageSourceDirs = [
                    "src/main/clojure",
                    "src/main/groovy",
                    "src/main/java",
                    "src/main/kotlin",
                    "src/main/scala",
                    "src/$buildTypeName/clojure",
                    "src/$buildTypeName/groovy",
                    "src/$buildTypeName/java",
                    "src/$buildTypeName/kotlin",
                    "src/$buildTypeName/scala"
            ]

            if (productFlavorName) {
                coverageSourceDirs.add("src/$productFlavorName/clojure")
                coverageSourceDirs.add("src/$productFlavorName/groovy")
                coverageSourceDirs.add("src/$productFlavorName/java")
                coverageSourceDirs.add("src/$productFlavorName/kotlin")
                coverageSourceDirs.add("src/$productFlavorName/scala")
            }

            additionalSourceDirs = subProject.files(coverageSourceDirs)
            sourceDirectories = subProject.files(coverageSourceDirs)

//            if (combined) {
//                // add instrumentation coverage execution data
//                executionData += subProject.fileTree("${subProject.buildDir}/outputs/code_coverage").matching {
//                    include "**/*.ec"
//                }
//            }

            // add if true in extension or for the unit test Jacoco task
            def addToMergeTask = !combined || extension.includeInstrumentationCoverageInMergedReport

//            if (mergeTask != null && addToMergeTask) {
//                mergeTask.executionData += executionData
//            }
            if (mergedReportTask != null && addToMergeTask) {
                mergedReportTask.classDirectories += classDirectories
                mergedReportTask.additionalSourceDirs += additionalSourceDirs
                mergedReportTask.sourceDirectories += sourceDirectories
            }
        }

//        subProject.check.dependsOn "${taskName}"
    }

    protected static addJacocoMergeToRootProject(final Project project, final JunitJacocoExtension extension) {
        project.plugins.apply('jacoco')

        project.afterEvaluate {
            // Apply the Jacoco version after evaluating the project so that the extension could be configured
            project.jacoco {
                toolVersion extension.jacocoVersion
            }
        }

        def mergeTask = project.task("mergeJacocoReports", type: JacocoMerge) {
            executionData project.files().asFileTree // Start with an empty collection.
            destinationFile project.file("${project.buildDir}/jacoco/mergedReport.ec")

            doFirst {
                // Filter non existing files.
                def realExecutionData = project.files().asFileTree

                executionData.each {
                    if (it.exists()) {
                        realExecutionData += project.files(it)
                    }
                }

                executionData = realExecutionData
            }
        }

        def mergedReportTask = project.task("jacocoTestReportMerged", type: JacocoReport, dependsOn: mergeTask) {
            executionData mergeTask.destinationFile

            reports {
                xml {
                    enabled = true
                    destination project.file("${project.buildDir}/reports/jacoco/jacoco.xml")
                }
                csv {
                    enabled = true
                    destination project.file("${project.buildDir}/reports/jacoco/jacoco.csv")
                }
                html {
                    enabled = true
                    destination project.file("${project.buildDir}/reports/jacoco")
                }
            }

            // Start with empty collections.
            classDirectories = project.files()
            additionalSourceDirs = project.files()
            sourceDirectories = project.files()
        }

        return [mergeTask, mergedReportTask]
    }

    static List<String> getExcludes(final JunitJacocoExtension extension) {
        extension.excludes == null ? [
                '**/R.class',
                '**/R2.class', // ButterKnife Gradle Plugin.
                '**/R$*.class',
                '**/R2$*.class', // ButterKnife Gradle Plugin.
                '**/*$$*',
                '**/*$ViewInjector*.*', // Older ButterKnife Versions.
                '**/*$ViewBinder*.*', // Older ButterKnife Versions.
                '**/*_ViewBinding*.*', // Newer ButterKnife Versions.
                '**/BuildConfig.*',
                '**/Manifest*.*',
                '**/*$Lambda$*.*', // Jacoco can not handle several "$" in class name.
                '**/*Dagger*.*', // Dagger auto-generated code.
                '**/*MembersInjector*.*', // Dagger auto-generated code.
                '**/*_Provide*Factory*.*', // Dagger auto-generated code.
                '**/*_Factory*.*', // Dagger auto-generated code.
                '**/*$JsonObjectMapper.*', // LoganSquare auto-generated code.
                '**/*$inlined$*.*', // Kotlin specific, Jacoco can not handle several "$" in class name.
                '**/*$Icepick.*', // Icepick auto-generated code.
                '**/*$StateSaver.*', // android-state auto-generated code.
                '**/*AutoValue_*.*' // AutoValue auto-generated code.
        ] : extension.excludes
    }

    private static boolean isAndroidProject(final Project project) {
        final boolean isAndroidLibrary = project.plugins.hasPlugin('com.android.library')
        final boolean isAndroidApp = project.plugins.hasPlugin('com.android.application')
        final boolean isAndroidTest = project.plugins.hasPlugin('com.android.test')
        final boolean isAndroidFeature = project.plugins.hasPlugin('com.android.feature')
        final boolean isAndroidInstantApp = project.plugins.hasPlugin('com.android.instantapp')
        return isAndroidLibrary || isAndroidApp || isAndroidTest || isAndroidFeature || isAndroidInstantApp
    }

    private static boolean isJavaProject(final Project project) {
        final boolean isJava = project.plugins.hasPlugin('java')
        final boolean isJavaLibrary = project.plugins.hasPlugin('java-library')
        final boolean isJavaGradlePlugin = project.plugins.hasPlugin('java-gradle-plugin')
        return isJava || isJavaLibrary || isJavaGradlePlugin
    }

    protected static boolean isKotlinAndroid(final Project project) {
        return project.plugins.hasPlugin('org.jetbrains.kotlin.android')
    }

    protected static boolean isKotlinMultiplatform(final Project project) {
        return project.plugins.hasPlugin('org.jetbrains.kotlin.multiplatform')
    }

    protected static boolean isAndroidApplication(final Project project) {
        return project.plugins.hasPlugin('com.android.application')
    }

    protected static boolean isAndroidLibrary(final Project project) {
        return project.plugins.hasPlugin('com.android.library')
    }

    protected static boolean isAndroidFeature(final Project project) {
        return project.plugins.hasPlugin('com.android.feature')
    }

    private static boolean shouldIgnore(final Project project, final JunitJacocoExtension extension) {
        if (extension.ignoreProjects?.contains(project.name) || extension.ignoreProjects?.contains(project.path)) {
            // Regex could be slower.
            return true
        }

        if (extension.ignoreProjects != null) {
            for (String ignoredProject : extension.ignoreProjects) {
                if (project.name.find(ignoredProject) || project.path.find(ignoredProject)) {
                    return true
                }
            }
        }

        return false
    }
}