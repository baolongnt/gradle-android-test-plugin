import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestReport

class AndroidTestPlugin implements Plugin<Project> {

    private static final String TEST_DIR = 'test'
    private static final String TEST_TASK_NAME = 'test'
    private static final String TEST_CLASSES_DIR = 'test-classes'
    private static final String TEST_REPORT_DIR = 'test-report'

    @Override
    void apply(Project project) {
        Project androidProject = project.project(":" + project.name.replace("-test", ""))

        def hasAppPlugin = androidProject.plugins.hasPlugin "android"
        def hasLibraryPlugin = androidProject.plugins.hasPlugin "android-library"
        def log = project.logger

        // Ensure the Android plugin has been added in app or library form, but not both.
        if (!hasAppPlugin && !hasLibraryPlugin) {
            throw new IllegalStateException("The 'android' or 'android-library' plugin is required.")
        } else if (hasAppPlugin && hasLibraryPlugin) {
            throw new IllegalStateException(
                    "Having both 'android' and 'android-library' plugin is not supported.")
        }

        // Get the 'test' configuration for test-only dependencies.
        def testConfiguration = project.configurations.getByName('testCompile')

        // Replace the root 'test' task for running all unit tests.
        def testTask = project.tasks.replace(TEST_TASK_NAME, TestReport)
        testTask.destinationDir = project.file("$project.buildDir/$TEST_REPORT_DIR")
        testTask.description = 'Runs all unit tests.'
        testTask.group = JavaBasePlugin.VERIFICATION_GROUP
        // Add our new task to Gradle's standard "check" task.
        project.tasks.check.dependsOn testTask

        Plugin androidPlugin = androidProject.plugins.getPlugin(hasAppPlugin ? "android" : "android-library");
        def androidRuntime = androidPlugin.getRuntimeJarList().join(File.pathSeparator)

        def variants = hasAppPlugin ? androidProject.android.applicationVariants :
                androidProject.android.libraryVariants

        variants.all { variant ->
            // Get the build type name (e.g., "Debug", "Release").
            def buildTypeName = variant.buildType.name.capitalize()
            def projectFlavorNames = variant.productFlavors.collect { it.name.capitalize() }
            def projectFlavorName = projectFlavorNames.join()
            // The combination of flavor and type yield a unique "variation". This value is used for
            // looking up existing associated tasks as well as naming the task we are about to create.
            def variationName = "$projectFlavorName$buildTypeName"
            // Grab the task which outputs the merged manifest, resources, and assets for this flavor.
            def processedManifestPath = variant.processManifest.manifestOutputFile
            def processedResourcesPath = variant.mergeResources.outputDir
            def processedAssetsPath = variant.mergeAssets.outputDir

            def testSrcDirs = []
            testSrcDirs.add(project.file("src/$TEST_DIR/java"))
            testSrcDirs.add(project.file("src/$TEST_DIR$buildTypeName/java"))
            testSrcDirs.add(project.file("src/$TEST_DIR$projectFlavorName/java"))
            projectFlavorNames.each { flavor ->
                testSrcDirs.add project.file("src/$TEST_DIR$flavor/java")
            }

            JavaPluginConvention javaConvention = project.convention.getPlugin JavaPluginConvention
            SourceSet variationSources = javaConvention.sourceSets.create "test$variationName"
            variationSources.resources.srcDirs project.file("src/$TEST_DIR/resources")
            variationSources.java.setSrcDirs testSrcDirs

            log.debug("----------------------------------------")
            log.debug("build type name: $buildTypeName")
            log.debug("project flavor name: $projectFlavorName")
            log.debug("variation name: $variationName")
            log.debug("manifest: $processedManifestPath")
            log.debug("resources: $processedResourcesPath")
            log.debug("assets: $processedAssetsPath")
            log.debug("test sources: $variationSources.java.asPath")
            log.debug("test resources: $variationSources.resources.asPath")
            log.debug("----------------------------------------")

            def javaCompile = variant.javaCompile;

            // Add the corresponding java compilation output to the 'testCompile' configuration to
            // create the classpath for the test file compilation.
            def testCompileClasspath = testConfiguration.plus project.files(javaCompile.destinationDir, javaCompile.classpath)

            def testDestinationDir = project.files(
                    "$project.buildDir/$TEST_CLASSES_DIR/$variant.dirName")

            // Create a task which compiles the test sources.
            def testCompileTask = project.tasks.getByName variationSources.compileJavaTaskName
            // Depend on the project compilation (which itself depends on the manifest processing task).
            testCompileTask.dependsOn javaCompile
            testCompileTask.group = null
            testCompileTask.description = null
            testCompileTask.classpath = testCompileClasspath
            testCompileTask.source = variationSources.java
            testCompileTask.destinationDir = testDestinationDir.getSingleFile()
            testCompileTask.doFirst {
                testCompileTask.options.bootClasspath = androidRuntime
            }

            // Clear out the group/description of the classes plugin so it's not top-level.
            def testClassesTask = project.tasks.getByName variationSources.classesTaskName
            testClassesTask.group = null
            testClassesTask.description = null

            // Add the output of the test file compilation to the existing test classpath to create
            // the runtime classpath for test execution.
            def testRunClasspath = testCompileClasspath.plus testDestinationDir
            testRunClasspath.add project.files("$project.buildDir/resources/test$variationName")

            // Create a task which runs the compiled test classes.
            def taskRunName = "$TEST_TASK_NAME$variationName"
            def testRunTask = project.tasks.create(taskRunName, Test)
            testRunTask.dependsOn testClassesTask
            testRunTask.inputs.sourceFiles.from.clear()
            testRunTask.classpath = testRunClasspath
            testRunTask.testClassesDir = testCompileTask.destinationDir
            testRunTask.group = JavaBasePlugin.VERIFICATION_GROUP
            testRunTask.description = "Run unit tests for Build '$variationName'."
            // TODO Gradle 1.7: testRunTask.reports.html.destination =
            testRunTask.testReportDir =
                    project.file("$project.buildDir/$TEST_REPORT_DIR/$variant.dirName")
            testRunTask.doFirst {
                // Prepend the Android runtime onto the classpath.
                testRunTask.classpath = project.files(androidRuntime).plus testRunClasspath
            }

            // Work around http://issues.gradle.org/browse/GRADLE-1682
            testRunTask.scanForTestClasses = false
            testRunTask.include '**/*Test.class'
            testRunTask.include '**/*Spec.class'

            // Add the path to the correct manifest, resources, assets as a system property.
            testRunTask.systemProperties.put('android.manifest', processedManifestPath)
            testRunTask.systemProperties.put('android.resources', processedResourcesPath)
            testRunTask.systemProperties.put('android.assets', processedAssetsPath)

            testTask.reportOn testRunTask
        }
    }
}
