pipeline {
   agent any 
  tools { 
        maven 'maven 3.3.9' 
        jdk 'jdk 8.0.1' 
    }
   
   stages {
      stage('smarthome-build') { 
   steps {
              //sh 'chmod +x build.sh'
            //sh './build.sh'    
           sh 'mvn clean install'
        }
      }
   }
}
