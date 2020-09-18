package projects

import Gradle_Check.configurations.FunctionalTestsPass
import Gradle_Check.configurations.PerformanceTestsPass
import Gradle_Check.model.FunctionalTestBucketProvider
import Gradle_Check.model.PerformanceTestBucketProvider
import Gradle_Check.model.PerformanceTestCoverage
import common.Os
import configurations.FunctionalTest
import configurations.PerformanceTestCoordinator
import configurations.SanityCheck
import configurations.buildReportTab
import jetbrains.buildServer.configs.kotlin.v2019_2.AbsoluteId
import jetbrains.buildServer.configs.kotlin.v2019_2.BuildType
import jetbrains.buildServer.configs.kotlin.v2019_2.FailureAction
import jetbrains.buildServer.configs.kotlin.v2019_2.IdOwner
import jetbrains.buildServer.configs.kotlin.v2019_2.Project
import model.CIBuildModel
import model.PerformanceTestType
import model.SpecificBuild
import model.Stage
import model.StageNames
import model.TestType

class StageProject(model: CIBuildModel, functionalTestBucketProvider: FunctionalTestBucketProvider, performanceTestBucketProvider: PerformanceTestBucketProvider, stage: Stage, rootProjectUuid: String) : Project({
    this.uuid = "${model.projectPrefix}Stage_${stage.stageName.uuid}"
    this.id = AbsoluteId("${model.projectPrefix}Stage_${stage.stageName.id}")
    this.name = stage.stageName.stageName
    this.description = stage.stageName.description
}) {
    val specificBuildTypes: List<BuildType>

    val performanceTests: List<PerformanceTestCoordinator>

    val functionalTests: List<FunctionalTest>

    init {
        features {
            if (stage.specificBuilds.contains(SpecificBuild.SanityCheck)) {
                buildReportTab("API Compatibility Report", "report-architecture-test-binary-compatibility-report.html")
                buildReportTab("Incubating APIs Report", "incubation-reports/all-incubating.html")
            }
            if (stage.performanceTests.isNotEmpty()) {
                buildReportTab("Performance", "report-performance-performance-tests.zip!report/index.html")
            }
        }

        specificBuildTypes = stage.specificBuilds.map {
            it.create(model, stage)
        }
        specificBuildTypes.forEach(this::buildType)

        performanceTests = stage.performanceTests.map { PerformanceTestCoordinator(model, it, stage) }
        performanceTests.forEach(this::buildType)

        if (stage.stageName == StageNames.EXPERIMENTAL_PERFORMANCE) {
            createPerformanceTests(model, performanceTestBucketProvider, stage, PerformanceTestCoverage(PerformanceTestType.test, Os.LINUX))
            createPerformanceTests(model, performanceTestBucketProvider, stage, PerformanceTestCoverage(PerformanceTestType.slow, Os.LINUX))
            createPerformanceTests(model, performanceTestBucketProvider, stage, PerformanceTestCoverage(PerformanceTestType.historical, Os.LINUX))
            createPerformanceTests(model, performanceTestBucketProvider, stage, PerformanceTestCoverage(PerformanceTestType.flakinessDetection, Os.LINUX))
            createPerformanceTests(model, performanceTestBucketProvider, stage, PerformanceTestCoverage(PerformanceTestType.experiment, Os.LINUX))
        }

        val (topLevelCoverage, allCoverage) = stage.functionalTests.partition { it.testType == TestType.soak || it.testDistribution }
        val topLevelFunctionalTests = topLevelCoverage
            .map { FunctionalTest(model, it.asConfigurationId(model), it.asName(), it.asName(), it, stage = stage) }
        topLevelFunctionalTests.forEach(this::buildType)

        val functionalTestProjects = allCoverage
            .map { testCoverage ->
                val functionalTestProject = FunctionalTestProject(model, functionalTestBucketProvider, testCoverage, stage)
                if (stage.functionalTestsDependOnSpecificBuilds) {
                    specificBuildTypes.forEach { specificBuildType ->
                        functionalTestProject.addDependencyForAllBuildTypes(specificBuildType)
                    }
                }
                if (!(stage.functionalTestsDependOnSpecificBuilds && stage.specificBuilds.contains(SpecificBuild.SanityCheck)) && stage.dependsOnSanityCheck) {
                    functionalTestProject.addDependencyForAllBuildTypes(AbsoluteId(SanityCheck.buildTypeId(model)))
                }
                functionalTestProject
            }

        functionalTestProjects.forEach { functionalTestProject ->
            this@StageProject.subProject(functionalTestProject)
            this@StageProject.buildType(FunctionalTestsPass(model, functionalTestProject))
        }

        val deferredTestsForThisStage = functionalTestBucketProvider.createDeferredFunctionalTestsFor(stage)
        if (deferredTestsForThisStage.isNotEmpty()) {
            val deferredTestsProject = Project {
                uuid = "${rootProjectUuid}_deferred_tests"
                id = AbsoluteId(uuid)
                name = "Test coverage deferred from Quick Feedback and Ready for Merge"
                deferredTestsForThisStage.forEach(this::buildType)
            }
            subProject(deferredTestsProject)
        }

        functionalTests = topLevelFunctionalTests + functionalTestProjects.flatMap(FunctionalTestProject::functionalTests) + deferredTestsForThisStage
    }

    private fun createPerformanceTests(model: CIBuildModel, performanceTestBucketProvider: PerformanceTestBucketProvider, stage: Stage, performanceTestCoverage: PerformanceTestCoverage) {
        val performanceTestProject = PerformanceTestProject(model, performanceTestBucketProvider, stage, performanceTestCoverage)
        subProject(performanceTestProject)
        buildType(PerformanceTestsPass(model, performanceTestProject))
    }
}

private fun FunctionalTestProject.addDependencyForAllBuildTypes(dependency: IdOwner) {
    functionalTests.forEach { functionalTestBuildType ->
        functionalTestBuildType.dependencies {
            dependency(dependency) {
                snapshot {
                    onDependencyFailure = FailureAction.CANCEL
                    onDependencyCancel = FailureAction.CANCEL
                }
            }
        }
    }
}
