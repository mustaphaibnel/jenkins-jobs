pipelineJob('pull_deploy_website_service') {
    description('Pull and Deploy Job for Website Service with Environment Support')

    parameters {
        stringParam('ENVIRONMENT', 'staging', 'Environment name (e.g., staging, production)')
        stringParam('SERVICE_NAME', 'website', 'Service name (e.g., website-service)')
        stringParam('REPO_URL', 'https://github.com/viven-app/EnvFlex-Deploy.git', 'GitHub Repository URL')
        stringParam('BRANCH', 'staging', 'Git Branch')
        stringParam('AWS_ACCOUNT_ID', '041108090159', 'AWS Account ID')
        stringParam('ECR_REGION', 'us-east-1', 'AWS Region')
        stringParam('DOCKER_TAG', 'staging', 'Docker Tag to Use for the Image')
        stringParam('GITHUB_CREDENTIALS', 'staging-github-token', 'GitHub Credentials ID')
        stringParam('AWS_CREDENTIALS', 'staging-aws-credentials', 'AWS Credentials ID')
    }

    definition {
        cps {
            script("""
pipeline {
    agent any

    environment {
        AWS_REGION = "\${params.ECR_REGION}"
        AWS_ACCOUNT_ID = "\${params.AWS_ACCOUNT_ID}"
        DOCKER_TAG = "\${params.DOCKER_TAG}"
        ECR_URI = "\${AWS_ACCOUNT_ID}.dkr.ecr.\${AWS_REGION}.amazonaws.com/\${params.SERVICE_NAME}"
        GITHUB_CREDENTIALS = "\${params.GITHUB_CREDENTIALS}"
        AWS_CREDENTIALS = "\${params.AWS_CREDENTIALS}"
        SERVICE_NAME = "\${params.SERVICE_NAME}"

        // Website service configuration
        PORT = 80
        EXPOSE_PORT= 8000
        NODE_ENV = "production"
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
                    aws ecr get-login-password --region \$AWS_REGION | docker login --username AWS --password-stdin \$AWS_ACCOUNT_ID.dkr.ecr.\$AWS_REGION.amazonaws.com
                    '''
                }
            }
        }

        stage('Ensure Docker Network Exists') {
            steps {
                script {
                    sh '''
                    #!/bin/bash
                    if ! docker network ls | grep -q 'app-network'; then
                        docker network create app-network
                    fi
                    '''
                }
            }
        }

        stage('Remove Existing Service Container') {
            steps {
                script {
                    sh '''
                    #!/bin/bash
                    if [ "\$(docker ps -aq -f name=\${SERVICE_NAME})" ]; then
                        docker stop \${SERVICE_NAME} || true
                        docker rm \${SERVICE_NAME} || true
                    fi
                    '''
                }
            }
        }

        stage('Deploy Website Service') {
            steps {
                script {
                    sh '''
                    #!/bin/bash
                    # Running the website service container with environment variables
                    docker run -d \
                        --name \${SERVICE_NAME} \
                        --network app-network \
                        -e PORT=\${PORT} \
                        -e NODE_ENV=\${NODE_ENV} \
                        -p \${EXPOSE_PORT}:\${PORT} \
                        \${ECR_URI}:\${DOCKER_TAG}
                    '''
                }
            }
        }
    }

    post {
        always {
            cleanWs() // Clean up the workspace
        }
    }
}
            """)
        }
    }
}
