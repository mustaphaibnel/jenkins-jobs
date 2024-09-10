job('services_job') {
  scm {
    git {
      remote {
        url('https://github.com/mustaphaibnel/ssl-automate-manager-python')
      }
      branch('main')
    }
  }
}
