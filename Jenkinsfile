pipeline {
   agent any 
  tools { 
        maven 'maven 3.3.9' 
        jdk 'jdk 9.0.4' 
    }
   
   stages {
      stage('smarthome-build') { 
   steps {
              //sh 'chmod +x build.sh'
            //sh './build.sh'    
           sh 'mvn  build'
        }
      }
   }
}
