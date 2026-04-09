pipeline {
    agent any
    environment {
        // --- DATAIKU SETTINGS ---
        DESIGN_URL = "https://dss-83d16b63-ced39760-int2.gis-dataiker-2.getitstarted.dataiku.com" // Change if your DSS is on a different port
        DESIGN_API_KEY = credentials('DSS_API_KEY') // This uses the secret you stored in Jenkins
        DSS_PROJECT = "DKU_CHURN" // Go to DSS, open your project, look at the URL for the Key
        
        // --- TARGET SETTINGS (Automation Node) ---
        AUTO_PREPROD_ID = "pre_prod_automation_space-ced39760-int2_node-83d16b63"
        AUTO_PREPROD_URL = "https://pre-prod-automation-83d16b63-ced39760-int2.gis-dataiker-2.getitstarted.dataiku.com"
        AUTO_PREPROD_API_KEY = credentials('DSS_API_KEY_PREPROD')

        AUTO_PROD_ID = "automation_space-ced39760-int2_node-83d16b63"
        AUTO_PROD_URL = "https://automation-83d16b63-ced39760-int2.gis-dataiker-2.getitstarted.dataiku.com"
        AUTO_PROD_API_KEY = credentials('DSS_API_KEY_PROD')
        
        bundle_name = "${sh(returnStdout: true, script: 'echo "bundle_`date +%Y-%m-%d_%H-%m-%S`"').trim()}"
    }
    stages {
        stage('PREPARE'){
            steps {
                cleanWs()
                sh 'echo ${bundle_name}'
                git branch: 'main', credentialsId: "github-creds", url: "https://github.com/qingqingxue422/dss-pipeline-cicd.git"
                sh "cat requirements.txt"
                withPythonEnv('python3') {
                    sh "pip install -U pip"
                    sh "pip install -r requirements.txt"
                }
            }
        }
        stage('PROJECT_VALIDATION') {
            steps {
                withPythonEnv('python3') {
                    sh "pytest -s 1_project_validation/run_test.py -o junit_family=xunit1 --host='${DESIGN_URL}' --api='${DESIGN_API_KEY}' --project='${DSS_PROJECT}' --junitxml=reports/PROJECT_VALIDATION.xml"
                }
            }
        }
        stage('PACKAGE_BUNDLE') {
            steps {
                withPythonEnv('python3') {
                    sh "python 2_package_bundle/run_bundling.py '${DESIGN_URL}' '${DESIGN_API_KEY}' '${DSS_PROJECT}' ${bundle_name}"
                }
                sh "echo DSS project bundle created and downloaded in local workspace"
                sh "ls -la"
                script {
                    def server = Artifactory.server 'artifactory'
                    def uploadSpec = """{
                        "files": [{
                          "pattern": "*.zip",
                          "target": "generic-local/dss_bundle/"
                        }]
                    }"""
                    def buildInfo = server.upload spec: uploadSpec, failNoOp: true
                }
            }
        }
        stage('PREPROD_TEST') {
            steps {
                withPythonEnv('python3') {
                    sh "python 3_preprod_test/import_bundle.py '${DESIGN_URL}' '${DESIGN_API_KEY}' '${DSS_PROJECT}' ${bundle_name} '${AUTO_PREPROD_ID}'"
                    sh "pytest -s 3_preprod_test/run_test.py -o junit_family=xunit1 --host='${AUTO_PREPROD_URL}' --api='${AUTO_PREPROD_API_KEY}' --project='${DSS_PROJECT}' --junitxml=reports/PREPROD_TEST.xml"
                }                
            }
        }
        stage('DEPLOY_TO_PROD') {
            steps {
                withPythonEnv('python3') {
                    sh "python 4_deploy_prod/deploy_bundle.py '${DESIGN_URL}' '${DESIGN_API_KEY}' '${DSS_PROJECT}' '${bundle_name}' '${AUTO_PROD_ID}' ${AUTO_PROD_URL} ${AUTO_PROD_API_KEY}"
                }
            }
        }
    }
    post{
        always {
            fileOperations ([fileDeleteOperation(includes: '*.zip')])
            junit 'reports/**/*.xml'
      }
    }
}
