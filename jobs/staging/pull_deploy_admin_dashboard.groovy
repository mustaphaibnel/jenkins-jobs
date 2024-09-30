pipelineJob('pull_deploy_admin_dashboard_service') {
    description('Pull and Deploy Job for Admin Dashboard Service with Vite Configuration Support')

    parameters {
        stringParam('ENVIRONMENT', 'staging', 'Environment name (e.g., staging, production)')
        stringParam('SERVICE_NAME', 'admin-dashboard', 'Service name (e.g., admin-dashboard-service)')
        stringParam('REPO_URL', 'https://github.com/viven-app/EnvFlex-Deploy.git', 'GitHub Repository URL')
        stringParam('BRANCH', 'staging', 'Git Branch')
        stringParam('AWS_ACCOUNT_ID', '041108090159', 'AWS Account ID')
        stringParam('ECR_REGION', 'us-east-1', 'AWS Region')
        stringParam('DOCKER_TAG', 'staging', 'Docker Tag to Use for the Image')
        stringParam('GITHUB_CREDENTIALS', 'staging-github-token', 'GitHub Credentials ID')
        stringParam('AWS_CREDENTIALS', 'staging-aws-credentials', 'AWS Credentials ID')
        stringParam('VITE_BACKEND_URI', 'http://backend-service', 'Backend API URI for Vite')
        stringParam('VITE_BACKEND_PORT', '4000', 'Backend API Port for Vite')
        booleanParam('REMOVE_DB_VOLUME', false, 'Remove database volume before deployment if true')
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

        // Vite-based configuration
        VITE_BACKEND_URI = "\${params.VITE_BACKEND_URI}"
        VITE_BACKEND_PORT = "\${params.VITE_BACKEND_PORT}"

        // Admin Dashboard service configuration
        PORT = 3000
        NODE_ENV = "production"
        ACCESS_TOKEN_SECRET = "ADMIN_ACCESS_TOKEN_SECRET"
        REFRESH_TOKEN_SECRET = "ADMIN_REFRESH_TOKEN_SECRET"
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

        stage('Deploy Admin Dashboard Service') {
            steps {
                script {
                    sh '''
                    #!/bin/bash
                    # Running the admin dashboard service container with Vite environment variables
                    # Binding host port 3000 to container port 80 (Dockerfile exposes 80)
                    docker run -d \
                        --name \${SERVICE_NAME} \
                        --network app-network \
                        -e VITE_BACKEND_URI=\${VITE_BACKEND_URI} \
                        -e VITE_BACKEND_PORT=\${VITE_BACKEND_PORT} \
                        -e PORT=\${PORT} \
                        -e NODE_ENV=\${NODE_ENV} \
                        -e ACCESS_TOKEN_SECRET=\${ACCESS_TOKEN_SECRET} \
                        -e REFRESH_TOKEN_SECRET=\${REFRESH_TOKEN_SECRET} \
                        -p \${PORT}:80 \
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
