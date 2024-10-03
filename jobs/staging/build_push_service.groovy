pipelineJob('build_push_service') {
    description('Build and Push Job for Services with configurable parameters for AWS credentials, GitHub credentials, Docker tag, and AWS account.')

    parameters {
        choiceParam('SERVICE_NAME', ['user-service', 'content-service', 'auth-service', 'notification-service'], 'Select the service to build and push')
        stringParam('BRANCH', '', 'Git Branch for the selected service (leave blank to use default)')
        stringParam('ECR_REGION', 'us-east-1', 'AWS Region for ECR')
        stringParam('ENVIRONMENT', 'staging', 'Environment (e.g., staging, production)')
        stringParam('AWS_ACCOUNT_ID', '041108090159', 'AWS Account ID')
        stringParam('GITHUB_CREDENTIALS', 'staging-github-token', 'GitHub Credentials ID')
        stringParam('DOCKER_TAG', 'latest', 'Docker Tag to Use for the Image')
    }

    definition {
        cps {
            script("""
pipeline {
    agent any

    environment {
        SERVICE_NAME = "\${params.SERVICE_NAME}"
        AWS_REGION = "\${params.ECR_REGION}"
        AWS_ACCOUNT_ID = "\${params.AWS_ACCOUNT_ID}"
        GITHUB_CREDENTIALS = "\${params.GITHUB_CREDENTIALS}"
        AWS_CREDENTIALS = "\${params.ENVIRONMENT}-aws-credentials"  // Interpolate environment-based credentials
        DOCKER_TAG = "\${params.DOCKER_TAG}"
        ECR_URI = "\${AWS_ACCOUNT_ID}.dkr.ecr.\${AWS_REGION}.amazonaws.com/\${SERVICE_NAME}"
    }

    stages {
        stage('Set Repository Configuration') {
            steps {
                script {
                    def serviceConfigs = [
                        'user-service': [
                            repoUrl: 'https://github.com/onlysportsfan/user-service.git',
                            defaultBranch: 'alpha'
                        ],
                        'content-service': [
                            repoUrl: 'https://github.com/onlysportsfan/content-service.git',
                            defaultBranch: 'alpha'
                        ],
                        'auth-service': [
                            repoUrl: 'https://github.com/onlysportsfan/auth-service.git',
                            defaultBranch: 'alpha'
                        ],
                        'notification-service': [
                            repoUrl: 'https://github.com/onlysportsfan/notification-service.git',
                            defaultBranch: 'alpha'
                        ]
                    ]
                    def config = serviceConfigs[params.SERVICE_NAME]
                    env.REPO_URL = config.repoUrl
                    // Use the branch specified by the user, or default to the service's default branch
                    env.BRANCH = params.BRANCH ?: config.defaultBranch
                }
            }
        }

        stage('Checkout') {
            steps {
                git branch: "\${env.BRANCH}",
                    url: "\${env.REPO_URL}",
                    credentialsId: "\${env.GITHUB_CREDENTIALS}"
            }
        }

        stage('Login to AWS ECR') {
            steps {
                withAWS(credentials: "\${env.AWS_CREDENTIALS}", region: "\${env.AWS_REGION}") {
                    sh '''
                    #!/bin/bash
                    aws ecr get-login-password --region \${AWS_REGION} | docker login --username AWS --password-stdin \${AWS_ACCOUNT_ID}.dkr.ecr.\${AWS_REGION}.amazonaws.com
                    '''
                }
            }
        }

        stage('Create ECR Repository if Not Exists') {
            steps {
                withAWS(credentials: "\${env.AWS_CREDENTIALS}", region: "\${env.AWS_REGION}") {
                    sh '''
                    #!/bin/bash
                    if ! aws ecr describe-repositories --repository-names \${SERVICE_NAME} --region \${AWS_REGION} > /dev/null 2>&1; then
                        echo "Repository \${SERVICE_NAME} does not exist, creating..."
                        aws ecr create-repository --repository-name \${SERVICE_NAME} --region \${AWS_REGION}
                    else
                        echo "Repository \${SERVICE_NAME} already exists."
                    fi
                    '''
                }
            }
        }

        stage('Build Docker Image') {
            steps {
                script {
                    docker.build("\${SERVICE_NAME}:\${DOCKER_TAG}")
                }
            }
        }

        stage('Tag Docker Image for ECR') {
            steps {
                sh '''
                #!/bin/bash
                docker tag \${SERVICE_NAME}:\${DOCKER_TAG} \${ECR_URI}:\${DOCKER_TAG}
                '''
            }
        }

        stage('Push Docker Image to ECR') {
            steps {
                sh '''
                #!/bin/bash
                docker push \${ECR_URI}:\${DOCKER_TAG}
                '''
            }
        }
    }

    post {
        always {
            cleanWs()
        }
    }
}
            """)
        }
    }
}
