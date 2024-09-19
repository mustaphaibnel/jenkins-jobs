pipelineJob('pull_deploy_user_service') {
    description('Pull and Deploy Job for User Service with Environment Support')

    parameters {
        stringParam('ENVIRONMENT', 'staging', 'Environment name (e.g., staging, production)')
        stringParam('SERVICE_NAME', 'user-service', 'Service name (e.g., user-service, building-service)')
        stringParam('DB_NAME', 'user_db', 'Database name associated with the service')
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
        DB_NAME = "\${params.DB_NAME}"
        SERVICE_NAME = "\${params.SERVICE_NAME}"

        SEQUELIZE_DB = "\${env.STAGING_USER_SERVICE_SEQUELIZE_DB}"
        SEQUELIZE_USERNAME = "\${env.STAGING_USER_SERVICE_SEQUELIZE_USERNAME}"
        SEQUELIZE_PASSWORD = "\${env.STAGING_USER_SERVICE_SEQUELIZE_PASSWORD}"
        SEQUELIZE_HOST = "\${env.STAGING_USER_SERVICE_SEQUELIZE_HOST}"
        SEQUELIZE_PORT = "\${env.STAGING_USER_SERVICE_SEQUELIZE_PORT}"
        NODE_ENV = "development"
        SERVICE_PORT = "\${env.STAGING_USER_SERVICE_PORT}"

        ACCESS_TOKEN_SECRET_ID = "\${params.ENVIRONMENT}-access-token-secret"
        REFRESH_TOKEN_SECRET_ID = "\${params.ENVIRONMENT}-refresh-token-secret"
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
                    # Check if the Docker network exists
                    if ! docker network ls | grep -q 'app-network'; then
                        echo "Creating Docker network 'app-network'..."
                        docker network create app-network
                    else
                        echo "Docker network 'app-network' already exists."
                    fi
                    '''
                }
            }
        }


        stage('Remove Existing Database and Volume') {
            steps {
                script {
                    sh '''
                    #!/bin/bash
                    DB_CONTAINER_NAME="\$SEQUELIZE_HOST"

                    if [ "$(docker ps -aq -f name=\$DB_CONTAINER_NAME)" ]; then
                        docker stop \$DB_CONTAINER_NAME || true
                        docker rm \$DB_CONTAINER_NAME || true
                        if [ "\$REMOVE_DB_VOLUME" == "true" ]; then
                            docker volume rm \${DB_CONTAINER_NAME}-volume || true
                        fi
                    fi
                    '''
                }
            }
        }


        stage('Deploy Database') {
            steps {
                script {
                    sh '''
                    #!/bin/bash
                    # Running the database container using the specified SEQUELIZE_HOST
                    docker run -d \
                        --name \${SEQUELIZE_HOST} \
                        --network app-network \
                        -e POSTGRES_DB=\${SEQUELIZE_DB} \
                        -e POSTGRES_USER=\${SEQUELIZE_USERNAME} \
                        -e POSTGRES_PASSWORD=\${SEQUELIZE_PASSWORD} \
                        -e PGPORT=\${SEQUELIZE_PORT} \
                        -p \${SEQUELIZE_PORT}:\${SEQUELIZE_PORT} \
                        postgres:latest
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

        stage('Deploy Service') {
            steps {
                script {
                    sh '''
                    #!/bin/bash
                    # Running the service container using environment variables passed directly
                    docker run -d \
                        --name \${SERVICE_NAME} \
                        --network app-network \
                        -e SEQUELIZE_DB=\${SEQUELIZE_DB} \
                        -e SEQUELIZE_USERNAME=\${SEQUELIZE_USERNAME} \
                        -e SEQUELIZE_PASSWORD=\${SEQUELIZE_PASSWORD} \
                        -e SEQUELIZE_HOST=\${SEQUELIZE_HOST} \
                        -e SEQUELIZE_PORT=\${SEQUELIZE_PORT} \
                        -e PORT=\${SERVICE_PORT} \
                        -e NODE_ENV=\${NODE_ENV} \
                        -e ACCESS_TOKEN_SECRET=\${ACCESS_TOKEN_SECRET_ID} \
                        -e REFRESH_TOKEN_SECRET=\${REFRESH_TOKEN_SECRET_ID} \
                        -p \${SERVICE_PORT}:\${SERVICE_PORT} \
                        \${ECR_URI}:\${DOCKER_TAG}
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
