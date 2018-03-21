pipeline {
   agent any 
  tools { 
        maven 'maven 3.3.9' 
        jdk 'jdk 9.0.4' 
    }
   
   stages {
      stage('smarthome-build') { 
        steps {
           environment {
                  MAVEN_OPTS = "Xms512m -Xmx1024m"
                    } 
              //sh 'chmod +x build.sh'
            //sh './build.sh'    
           sh ' mvn install'
        }
      }
   }
}
