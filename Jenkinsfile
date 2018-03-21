pipeline {
   agent any 
  tools { 
        maven 'maven 3.3.9' 
        jdk 'jdk 9.0.4' 
    }
   
   stages {
      stage('smarthome-build') { 
      MAVEN_OPTS = "Xmx1g"
   steps {
              //sh 'chmod +x build.sh'
            //sh './build.sh'    
           sh 'mvn -DskipTests=true clean install'
        }
      }
   }
}
