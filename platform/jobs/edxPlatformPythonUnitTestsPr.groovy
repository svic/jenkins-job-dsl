package platform

import org.yaml.snakeyaml.Yaml
import static org.edx.jenkins.dsl.JenkinsPublicConstants.JENKINS_PUBLIC_LOG_ROTATOR
import static org.edx.jenkins.dsl.JenkinsPublicConstants.JENKINS_PUBLIC_JUNIT_REPORTS
import static org.edx.jenkins.dsl.JenkinsPublicConstants.GHPRB_CANCEL_BUILDS_ON_UPDATE
import static org.edx.jenkins.dsl.JenkinsPublicConstants.GENERAL_PRIVATE_JOB_SECURITY
import static org.edx.jenkins.dsl.JenkinsPublicConstants.JENKINS_EDX_PLATFORM_TEST_NOTIFIER

/* stdout logger */
Map config = [:]
Binding bindings = getBinding()
config.putAll(bindings.getVariables())
PrintStream out = config['out']

/* Map to hold the k:v pairs parsed from the secret file */
Map ghprbMap = [
    admin: ['svic'],
    userWhiteList: ['svic'],
    orgWhiteList: ['raccoongang'],
]

// This script generates a lot of jobs. Here is the breakdown of the configuration options:
// Map exampleConfig = [ open: true/false if this job should be 'open' (use the default security scheme or not)
//                       jobName: name of the job
//                       flowWorkerLabel: name of worker to run the flow job on
//                       subsetjob: name of subset job run by this job (shard jobs)
//                       repoName: name of the github repo containing the edx-platform you want to test
//                       runCoverage: whether or not the shards should run unit tests through coverage, and then
//                       run the coverage job on the results
//                       coverageJob: name of the coverage job to run after the unit tests
//                       workerLabel: label of the worker to run the subset jobs on
//                       whiteListBranchRegex: regular expression to filter which branches of a particular repo
//                       can will trigger builds (via GHRPB)
//                       context: Github context used to report test status
//                       triggerPhrase: Github comment used to trigger this job
//                       targetBranch: branch of the edx-platform used as a comparison when running coverage.
//                       this value is passed from the python job to the coverage job and used as an environment
//                       variable
//                       defaultTestengbranch: default branch of the testeng-ci repo for this job
//                       commentOnly: true/false if this job should only be triggered by explicit comments on
//                       github. Default behavior: triggered by comments AND pull request updates
//                       ]

// Individual Job Configurations
Map publicJobConfig = [ open: true,
                        jobName: 'edx-platform-python-unittests-pr',
                        flowWorkerLabel: 'flow-worker-python',
                        subsetJob: 'edx-platform-test-subset',
                        repoName: 'edx-platform',
                        runCoverage: true,
                        coverageJob: 'edx-platform-unit-coverage',
                        workerLabel: 'jenkins-worker',
                        whitelistBranchRegex: /^((?!open-release\/).)*$/,
                        context: 'jenkins/python',
                        triggerPhrase: /.*jenkins\W+run\W+python.*/,
                        targetBranch: 'origin/tezt-rg',
                        defaultTestengBranch: 'master'
                        ]


Map publicGinkgoJobConfig = [ open: true,
                              jobName: 'ginkgo-python-unittests-pr',
                              flowWorkerLabel: 'flow-worker-python',
                              subsetJob: 'edx-platform-test-subset',
                              repoName: 'edx-platform',
                              runCoverage: true,
                              coverageJob: 'edx-platform-unit-coverage',
                              workerLabel: 'ginkgo-jenkins-worker',
                              whitelistBranchRegex: /open-release\/ginkgo.master/,
                              context: 'jenkins/ginkgo/python',
                              triggerPhrase: /.*ginkgo\W+run\W+python.*/,
                              targetBranch: 'origin/tez-rg',
                              defaultTestengBranch: 'origin/open-release/ginkgo.master'
                              ]

Map python3JobConfig = [ open: true,
                         jobName: 'edx-platform-python3-unittests-pr',
                         flowWorkerLabel: 'flow-worker-python',
                         subsetJob: 'edx-platform-test-subset',
                         repoName: 'edx-platform',
                         runCoverage: true,
                         coverageJob: 'edx-platform-unit-coverage',
                         workerLabel: 'jenkins-worker',
                         whitelistBranchRegex: /^((?!open-release\/).)*$/,
                         context: 'jenkins/python3.5/python',
                         triggerPhrase: /.*jenkins\W+run\W+py35-django111\W+python.*/,
                         targetBranch: 'origin/tezt-rg',
                         defaultTestengBranch: 'master',
                         commentOnly: true,
                         toxEnv: 'py35-django111'
                         ]

List jobConfigs = [ publicJobConfig,
                    publicGinkgoJobConfig,
                    python3JobConfig
                    ]

// Iterate over the job configs to create individual build flow jobs
jobConfigs.each { jobConfig ->

    buildFlowJob(jobConfig.jobName) {

        if (!jobConfig.open.toBoolean()) {
            authorization GENERAL_PRIVATE_JOB_SECURITY()
        }
        properties {
            githubProjectUrl("https://github.com/raccoongang/${jobConfig.repoName}/")
        }
        logRotator JENKINS_PUBLIC_LOG_ROTATOR(7)
        concurrentBuild()
        label(jobConfig.flowWorkerLabel)
        checkoutRetryCount(5)
        environmentVariables {
            env('SUBSET_JOB', jobConfig.subsetJob)
            env('REPO_NAME', jobConfig.repoName)
            env('RUN_COVERAGE', jobConfig.runCoverage)
            env('COVERAGE_JOB', jobConfig.coverageJob)
            env('TARGET_BRANCH', jobConfig.targetBranch)
            env('TOX_ENV', jobConfig.toxEnv)
        }
        parameters {
            stringParam('WORKER_LABEL', jobConfig.workerLabel, 'Jenkins worker for running the test subset jobs')
        }
        multiscm {
            git {
                remote {
                    url('https://github.com/raccoongang/testeng-ci.git')
                }
                branch(jobConfig.defaultTestengBranch)
                browser()
                extensions {
                    cleanBeforeCheckout()
                    relativeTargetDirectory('testeng-ci')
                }
            }
        }
        triggers {
            githubPullRequest {
                admins(ghprbMap['admin'])
                useGitHubHooks()
                userWhitelist(ghprbMap['userWhiteList'])
                orgWhitelist(ghprbMap['orgWhiteList'])
                triggerPhrase(jobConfig.triggerPhrase)
                if (jobConfig.commentOnly) {
                    onlyTriggerPhrase(true)
                }
                whiteListTargetBranches([jobConfig.whitelistBranchRegex])
                extensions {
                    commitStatus {
                        context(jobConfig.context)
                    }
                }
            }
        }
        configure GHPRB_CANCEL_BUILDS_ON_UPDATE(false)

        wrappers {
            timestamps()
        }

        dslFile('testeng-ci/jenkins/flow/pr/edx-platform-python-unittests-pr.groovy')
        publishers {
            archiveJunit(JENKINS_PUBLIC_JUNIT_REPORTS) {
                retainLongStdout()
            }
            // Only archive Coverage data when explicitly defined in the jobConfig to avoid build errors
            if (jobConfig.runCoverage) {
                publishHtml {
                    report("reports") {
                        reportFiles('diff_coverage_combined.html')
                        reportName('Diff Coverage Report')
                        keepAll()
                    }
                }
                configure { node ->
                    node / publishers << 'jenkins.plugins.shiningpanda.publishers.CoveragePublisher' {
                    }
                }
            }
            if (jobConfig.repoName == "edx-platform") {
                downstreamParameterized JENKINS_EDX_PLATFORM_TEST_NOTIFIER('${ghprbPullId}')
            }
        }
    }
}
