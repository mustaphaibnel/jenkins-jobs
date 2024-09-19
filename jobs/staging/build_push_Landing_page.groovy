pipelineJob('build_push_Landing_page') {
    description('Build and Push Job for Landing-page with configurable parameters for AWS credentials, GitHub credentials, Docker tag, and AWS account.')
    parameters {
        stringParam('REPO_URL', 'https://github.com/viven-app/Landing-page.git', 'GitHub Repository URL')
        stringParam('ORG_NAME', 'viven-app', 'Organization Name')
        stringParam('BRANCH', 'DEVOPS-1', 'Git Branch')
        stringParam('ECR_REPO', 'website', 'AWS ECR Repository Name')
        stringParam('ECR_REGION', 'us-east-1', 'AWS Region for ECR')
        stringParam('ENVIRONMENT', 'staging', 'Environment (e.g., staging, production)')
        stringParam('AWS_ACCOUNT_ID', '041108090159', 'AWS Account ID')
        stringParam('GITHUB_CREDENTIALS', 'staging-github-token', 'GitHub Credentials ID')
        stringParam('DOCKER_TAG', 'staging', 'Docker Tag to Use for the Image')
    }
    definition {
        cps {
            script("""
pipeline {
    agent any

    parameters {
        string(name: 'REPO_URL', defaultValue: 'https://github.com/viven-app/Landing-page.git', description: 'GitHub Repository URL')
        string(name: 'ORG_NAME', defaultValue: 'viven-app', description: 'Organization Name')
        string(name: 'BRANCH', defaultValue: 'DEVOPS-1', description: 'Git Branch')
        string(name: 'ECR_REPO', defaultValue: 'website', description: 'AWS ECR Repository Name')
        string(name: 'ECR_REGION', defaultValue: 'us-east-1', description: 'AWS Region for ECR')
        string(name: 'ENVIRONMENT', defaultValue: 'staging', description: 'Environment (e.g., staging, production)')
        string(name: 'AWS_ACCOUNT_ID', defaultValue: '041108090159', description: 'AWS Account ID')
        string(name: 'GITHUB_CREDENTIALS', defaultValue: 'staging-github-token', description: 'GitHub Credentials ID')
        string(name: 'DOCKER_TAG', defaultValue: 'staging', description: 'Docker Tag to Use for the Image')
    }

    environment {
        AWS_REGION = "\${params.ECR_REGION}"
        ECR_REPO = "\${params.ECR_REPO}"
        IMAGE_TAG = "\${params.DOCKER_TAG}"  // Use the passed tag
        AWS_ACCOUNT_ID = "\${params.AWS_ACCOUNT_ID}"
        GITHUB_CREDENTIALS = "\${params.GITHUB_CREDENTIALS}"
        AWS_CREDENTIALS = "\${params.ENVIRONMENT}-aws-credentials"  // Interpolate environment-based credentials
        ECR_URI = "\${AWS_ACCOUNT_ID}.dkr.ecr.\${AWS_REGION}.amazonaws.com/\${ECR_REPO}"
    }

    stages {
        stage('Checkout') {
            steps {
                git branch: "\${params.BRANCH}",
                    url: "\${params.REPO_URL}",
                    credentialsId: "\${env.GITHUB_CREDENTIALS}"
            }
        }

        stage('Login to AWS ECR') {
            steps {
                withAWS(credentials: "\${env.AWS_CREDENTIALS}", region: "\${env.AWS_REGION}") {
                    sh 'aws ecr get-login-password --region \${AWS_REGION} | docker login --username AWS --password-stdin \${AWS_ACCOUNT_ID}.dkr.ecr.\${AWS_REGION}.amazonaws.com'
                }
            }
        }

        stage('Create ECR Repository if Not Exists') {
            steps {
                withAWS(credentials: "\${env.AWS_CREDENTIALS}", region: "\${env.AWS_REGION}") {
                    script {
                        // Check if the repository exists, and create if it does not
                        sh '''
                        if ! aws ecr describe-repositories --repository-names \${ECR_REPO} --region \${AWS_REGION} > /dev/null 2>&1; then
                            echo "Repository \${ECR_REPO} does not exist, creating..."
                            aws ecr create-repository --repository-name \${ECR_REPO} --region \${AWS_REGION}
                        else
                            echo "Repository \${ECR_REPO} already exists."
                        fi
                        '''
                    }
                }
            }
        }

        stage('Build Docker Image') {
            steps {
                script {
                    docker.build("\${env.ECR_REPO}:\${env.IMAGE_TAG}")
                }
            }
        }

        stage('Tag Docker Image for ECR') {
            steps {
                script {
                    // Explicitly use bash
                    sh '''#!/bin/bash
                    docker tag \${ECR_REPO}:\${IMAGE_TAG} \${ECR_URI}:\${IMAGE_TAG}
                    '''
                }
            }
        }

        stage('Push Docker Image to ECR') {
            steps {
                script {
                    // Explicitly use bash
                    sh '''#!/bin/bash
                    docker push \${ECR_URI}:\${IMAGE_TAG}
                    '''
                }
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
