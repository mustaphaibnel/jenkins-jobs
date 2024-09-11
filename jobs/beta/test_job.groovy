job('test_job') {
    description('A simple test job to print a hello message for the beta environment.')

    steps {
        shell('echo "Hello from the beta environment!"')
    }
}
