pipelineJob('pull_deploy_gateway_core_service') {
    description('Pull and Deploy Job for Gateway Core Service with Environment Support')

    parameters {
        stringParam('ENVIRONMENT', 'staging', 'Environment name (e.g., staging, production)')
        stringParam('SERVICE_NAME', 'gateway-core', 'Service name (e.g., gateway-core-service)')
        stringParam('REPO_URL', 'https://github.com/viven-app/EnvFlex-Deploy.git', 'GitHub Repository URL')
        stringParam('BRANCH', 'staging', 'Git Branch')
        stringParam('AWS_ACCOUNT_ID', '041108090159', 'AWS Account ID')
        stringParam('ECR_REGION', 'us-east-1', 'AWS Region')
        stringParam('DOCKER_TAG', 'staging', 'Docker Tag to Use for the Image')
        stringParam('GITHUB_CREDENTIALS', 'staging-github-token', 'GitHub Credentials ID')
        stringParam('AWS_CREDENTIALS', 'staging-aws-credentials', 'AWS Credentials ID')
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

        // Using default service names within the Docker network
        PORT = 4000
        NODE_ENV = "development"
        ASSISTANCE_SERVICE_URL = "http://assistance-service:4005/graphql"
        COMMUNICATION_SERVICE_URL = "http://communication-service:4004/graphql"
        CONTENT_SERVICE_URL = "http://content-service:4003/graphql"
        BUILDING_SERVICE_URL = "http://building-service:4002/graphql"
        USER_SERVICE_URL = "http://user-service:4001/graphql"
        ACCESS_TOKEN_SECRET = "ACCESS_TOKEN_SECRET"
        REFRESH_TOKEN_SECRET = "REFRESH_TOKEN_SECRET"
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

        stage('Deploy Gateway Core Service') {
            steps {
                script {
                    sh '''
                    #!/bin/bash
                    # Running the gateway core service container with environment variables
                    docker run -d \
                        --name \${SERVICE_NAME} \
                        --network app-network \
                        -e PORT=\${PORT} \
                        -e NODE_ENV=\${NODE_ENV} \
                        -e ASSISTANCE_SERVICE_URL=\${ASSISTANCE_SERVICE_URL} \
                        -e COMMUNICATION_SERVICE_URL=\${COMMUNICATION_SERVICE_URL} \
                        -e CONTENT_SERVICE_URL=\${CONTENT_SERVICE_URL} \
                        -e BUILDING_SERVICE_URL=\${BUILDING_SERVICE_URL} \
                        -e USER_SERVICE_URL=\${USER_SERVICE_URL} \
                        -e ACCESS_TOKEN_SECRET=\${ACCESS_TOKEN_SECRET} \
                        -e REFRESH_TOKEN_SECRET=\${REFRESH_TOKEN_SECRET} \
                        -p \${PORT}:\${PORT} \
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
