pipelineJob('pull_deploy_service') {
    description('Pull and Deploy Job for Services with Environment Support')

    parameters {
        stringParam('ENVIRONMENT', 'staging', 'Environment name (e.g., staging, production)')
        choiceParam('SERVICE_NAME', ['user-service', 'content-service', 'communication-service', 'assistance-service', 'building-service'], 'Select the service to deploy')
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

        // Non-sensitive default values
        NODE_ENV = 'development'  // Default value; can be overridden by credentials
    }

    stages {
        stage('Load Environment Variables') {
            steps {
                script {
                    // Construct the base credential ID prefix
                    def envPrefix = "\${params.ENVIRONMENT}-\${params.SERVICE_NAME}"

                    // Load sensitive variables using withCredentials
                    withCredentials([
                        string(credentialsId: "\${envPrefix}-sequelize-db", variable: 'SEQUELIZE_DB'),
                        string(credentialsId: "\${envPrefix}-sequelize-username", variable: 'SEQUELIZE_USERNAME'),
                        string(credentialsId: "\${envPrefix}-sequelize-password", variable: 'SEQUELIZE_PASSWORD'),
                        string(credentialsId: "\${envPrefix}-sequelize-host", variable: 'SEQUELIZE_HOST'),
                        string(credentialsId: "\${envPrefix}-sequelize-port", variable: 'SEQUELIZE_PORT'),
                        string(credentialsId: "\${envPrefix}-port", variable: 'SERVICE_PORT'),
                        string(credentialsId: "\${envPrefix}-node-env", variable: 'NODE_ENV'),
                        string(credentialsId: "\${params.ENVIRONMENT}-access-token-secret", variable: 'ACCESS_TOKEN_SECRET'),
                        string(credentialsId: "\${params.ENVIRONMENT}-refresh-token-secret", variable: 'REFRESH_TOKEN_SECRET')
                    ]) {
                        // Export variables to the environment
                        env.SEQUELIZE_DB = SEQUELIZE_DB
                        env.SEQUELIZE_USERNAME = SEQUELIZE_USERNAME
                        env.SEQUELIZE_PASSWORD = SEQUELIZE_PASSWORD
                        env.SEQUELIZE_HOST = SEQUELIZE_HOST
                        env.SEQUELIZE_PORT = SEQUELIZE_PORT
                        env.SERVICE_PORT = SERVICE_PORT
                        env.NODE_ENV = NODE_ENV
                        env.ACCESS_TOKEN_SECRET = ACCESS_TOKEN_SECRET
                        env.REFRESH_TOKEN_SECRET = REFRESH_TOKEN_SECRET
                    }
                }
            }
        }

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

        stage('Ensure Docker Network Exists') {
            steps {
                sh '''
                #!/bin/bash
                if ! docker network ls | grep -q 'app-network'; then
                    echo "Creating Docker network 'app-network'..."
                    docker network create app-network
                else
                    echo "Docker network 'app-network' already exists."
                fi
                '''
            }
        }

        stage('Remove Existing Database and Volume') {
            steps {
                sh '''
                #!/bin/bash
                set +x
                DB_CONTAINER_NAME="\$SEQUELIZE_HOST"

                if [ "\$(docker ps -aq -f name=\${DB_CONTAINER_NAME})" ]; then
                    docker stop \${DB_CONTAINER_NAME} || true
                    docker rm \${DB_CONTAINER_NAME} || true
                    if [ "\${REMOVE_DB_VOLUME}" == "true" ]; then
                        docker volume rm \${DB_CONTAINER_NAME}-volume || true
                    fi
                fi
                '''
            }
        }

        stage('Deploy Database') {
            steps {
                sh '''
                #!/bin/bash
                set +x
                docker run -d \\
                    --name "\$SEQUELIZE_HOST" \\
                    --network app-network \\
                    -e POSTGRES_DB="\$SEQUELIZE_DB" \\
                    -e POSTGRES_USER="\$SEQUELIZE_USERNAME" \\
                    -e POSTGRES_PASSWORD="\$SEQUELIZE_PASSWORD" \\
                    -e PGPORT="\$SEQUELIZE_PORT" \\
                    -p "\$SEQUELIZE_PORT":"\$SEQUELIZE_PORT" \\
                    postgres:latest
                '''
            }
        }

        stage('Remove Existing Service Container') {
            steps {
                sh '''
                #!/bin/bash
                set +x
                if [ "\$(docker ps -aq -f name=\${SERVICE_NAME})" ]; then
                    docker stop \${SERVICE_NAME} || true
                    docker rm \${SERVICE_NAME} || true
                fi
                '''
            }
        }

        stage('Deploy Service') {
            steps {
                sh '''
                #!/bin/bash
                set +x
                docker run -d \\
                    --name \${SERVICE_NAME} \\
                    --network app-network \\
                    -e SEQUELIZE_DB="\$SEQUELIZE_DB" \\
                    -e SEQUELIZE_USERNAME="\$SEQUELIZE_USERNAME" \\
                    -e SEQUELIZE_PASSWORD="\$SEQUELIZE_PASSWORD" \\
                    -e SEQUELIZE_HOST="\$SEQUELIZE_HOST" \\
                    -e SEQUELIZE_PORT="\$SEQUELIZE_PORT" \\
                    -e PORT="\$SERVICE_PORT" \\
                    -e NODE_ENV="\$NODE_ENV" \\
                    -e ACCESS_TOKEN_SECRET="\$ACCESS_TOKEN_SECRET" \\
                    -e REFRESH_TOKEN_SECRET="\$REFRESH_TOKEN_SECRET" \\
                    -p "\$SERVICE_PORT":"\$SERVICE_PORT" \\
                    \${ECR_URI}:\${DOCKER_TAG}
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
