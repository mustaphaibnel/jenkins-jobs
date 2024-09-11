job('credentials_job') {
    description('A job that reads credentials and environment variables for the qa environment.')

    steps {
        shell("""
            echo "Reading credentials for the qa environment"
            
            # DockerHub credentials
            echo "DockerHub Username Length: \$(echo -n "${QA_DOCKER_HUB_USERNAME}" | wc -c)"
            echo "DockerHub Password Length: \$(echo -n "${QA_DOCKER_HUB_PASSWORD}" | wc -c)"

            # AWS credentials
            echo "AWS Access Key Length: \$(echo -n "${QA_AWS_ACCESS_KEY_ID}" | wc -c)"
            echo "AWS Secret Key Length: \$(echo -n "${QA_AWS_SECRET_ACCESS_KEY}" | wc -c)"
            
            # GitHub token
            echo "GitHub Token Length: \$(echo -n "${QA_GITHUB_TOKEN}" | wc -c)"

            # Check global environment variables
            echo "Global environment variables:"
            echo "EXAMPLE_ENV: ${EXAMPLE_ENV}"
            echo "FOO: ${FOO}"
        """)
    }
}
