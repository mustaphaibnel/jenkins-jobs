pipelineJob('build_push_admin_dashboard') {
    description('Build and Push Job for admin-dashboard with configurable parameters for AWS credentials, GitHub credentials, Docker tag, and AWS account.')

    parameters {
        stringParam('REPO_URL', 'https://github.com/viven-app/admin-dashboard.git', 'GitHub Repository URL')
        stringParam('ORG_NAME', 'viven-app', 'Organization Name')
        stringParam('BRANCH', 'DEVOPS-1', 'Git Branch')
        stringParam('ECR_REPO', 'admin-dashboard', 'AWS ECR Repository Name')
        stringParam('ECR_REGION', 'us-east-1', 'AWS Region for ECR')
        stringParam('ENVIRONMENT', 'staging', 'Environment (e.g., staging, production)')
        stringParam('AWS_ACCOUNT_ID', '041108090159', 'AWS Account ID')
        stringParam('GITHUB_CREDENTIALS', 'staging-github-token', 'GitHub Credentials ID')
        stringParam('DOCKER_TAG', 'staging', 'Docker Tag to Use for the Image')
        stringParam('VITE_BACKEND_URI', 'http://backend-service', 'Backend API URI')
        stringParam('VITE_BACKEND_PORT', '4000', 'Backend API Port')
    }

    definition {
        cps {
            script("""
pipeline {
    agent any

    environment {
        AWS_REGION = "\${params.ECR_REGION}"
        ECR_REPO = "\${params.ECR_REPO}"
        IMAGE_TAG = "\${params.DOCKER_TAG}"
        AWS_ACCOUNT_ID = "\${params.AWS_ACCOUNT_ID}"
        GITHUB_CREDENTIALS = "\${params.GITHUB_CREDENTIALS}"
        AWS_CREDENTIALS = "\${params.ENVIRONMENT}-aws-credentials"
        ECR_URI = "\${AWS_ACCOUNT_ID}.dkr.ecr.\${AWS_REGION}.amazonaws.com/\${ECR_REPO}"
        VITE_BACKEND_URI = "\${params.VITE_BACKEND_URI}"
        VITE_BACKEND_PORT = "\${params.VITE_BACKEND_PORT}"
    }

    stages {
        stage('Checkout') {
            steps {
                git branch: "\${params.BRANCH}",
                    url: "\${params.REPO_URL}",
                    credentialsId: "\${GITHUB_CREDENTIALS}"
            }
        }

        stage('Login to AWS ECR') {
            steps {
                withAWS(credentials: "\${AWS_CREDENTIALS}", region: "\${AWS_REGION}") {
                    sh '''
                    #!/bin/bash
                    aws ecr get-login-password --region \${AWS_REGION} | docker login --username AWS --password-stdin \${AWS_ACCOUNT_ID}.dkr.ecr.\${AWS_REGION}.amazonaws.com
                    '''
                }
            }
        }

        stage('Create ECR Repository if Not Exists') {
            steps {
                withAWS(credentials: "\${AWS_CREDENTIALS}", region: "\${AWS_REGION}") {
                    sh '''
                    #!/bin/bash
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

        stage('Build Docker Image') {
            steps {
                sh '''
                #!/bin/bash
                docker build -t \${ECR_REPO}:\${IMAGE_TAG} --build-arg VITE_BACKEND_URI=\${VITE_BACKEND_URI} --build-arg VITE_BACKEND_PORT=\${VITE_BACKEND_PORT} .
                '''
            }
        }

        stage('Tag Docker Image for ECR') {
            steps {
                sh '''
                #!/bin/bash
                docker tag \${ECR_REPO}:\${IMAGE_TAG} \${ECR_URI}:\${IMAGE_TAG}
                '''
            }
        }

        stage('Push Docker Image to ECR') {
            steps {
                sh '''
                #!/bin/bash
                docker push \${ECR_URI}:\${IMAGE_TAG}
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
