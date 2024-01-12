#!/bin/bash

usage() {
   echo "Usage"
   echo "\
Payara Eclipselink Build script
================================

Usage: $0 <command>

Where command is:
   prepare  Creates required direcory $HOME/extension.lib.external, downloads and unpacks required libs
   compile  Obviously  
   install  To install compilation result to your maven repo
   deploy   To deploy the compilation result into patched projects repo

The tool will determine the correct version automatically no furger arguments are needed (or implemented)

Environment:
   M2_HOME,
   ANT_HOME poining at respective tool instalations
   
   STAGE    location of staging repo (target/stagerepo by default)
   REPO     location of PatchedProjects repo (by default ../Payara_PatchedProjects)
"
}
compile() {
   $ANT -f antbuild.xml -Dversion.qualifier=$QUALIFIER -Declipse.install.dir=$HOME/extension.lib.external/eclipse clean clean-runtime build-src
}

patch() {
   MODULES=$1/glassfish/modules
   if [[ ! -d $MODULES ]] ; then
      usage
      echo ""
      echo "$1 does not point to Payara install root, glassfish/modules directory not found"
      exit 1
   fi
   
   find $MODULES -name org.eclipse.persistence* -printf %P\\n | \
     grep -v org.eclipse.persistence.jpa.modelgen.processor | \
     sed  -e s/.jar// | \
     xargs -I{} sh -c "cp plugins/{}_*.jar  $MODULES/{}.jar"
}

localRepo() {
   $MVN help:evaluate -Dexpression="settings.localRepository" | grep -v [INFO]
 }

install() {
   TARGET=$STAGE
   if [[ -d $1 ]] ; then
     TARGET=$1
   fi
   rm pom.xml # That one is just temporary created during uploads
   
   # rename asm files, their name from compile are different than required by install
   ASM_VERSION=`grep 'eclipselink.asm.version' buildsystem/compdeps/pom.xml | head -1 | awk 'match($0, /.*[>]([0-9.]+)[<].*/, v) {print v[1]}'`
   echo "ASM_VERSION = ${ASM_VERSION}"
   mv plugins/org.eclipse.persistence.asm-sources.jar plugins/org.eclipse.persistence.asm.source_${ASM_VERSION}.jar
   mv plugins/org.eclipse.persistence.asm.jar plugins/org.eclipse.persistence.asm_${ASM_VERSION}.jar
   
   $MVN dependency:copy -Dartifact=org.apache.maven:maven-ant-tasks:2.0.8:jar -DoutputDirectory=target/
   $ANT -f uploadToMaven.xml -Dmavenant.dir=target/ -Drelease.version=$VERSION -Dbuild.type=RELEASE -Dgit.hash=`git rev-parse --short HEAD` -Dversion.string=$VERSION -Dmaven.repo.dir=$TARGET -Dasm.version=${ASM_VERSION}  
}

deploy() {
   rm pom.xml
   $MVN wagon:merge-maven-repos -Dwagon.source=file://`realpath $STAGE` -Dwagon.target=file://`realpath $REPO`
}

prepare() {
   echo "*************************************************************"
   echo "**  Polluting your home with extension.lib.external!!!     **"
   echo "*************************************************************"
   mkdir -p $HOME/extension.lib.external/mavenant
   mkdir -p $HOME/extension.lib.external/plugins

   wget -nc https://repo1.maven.org/maven2/junit/junit/4.12/junit-4.12.jar -O $HOME/extension.lib.external/junit-4.12.jar
   wget -nc https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar -O $HOME/extension.lib.external/hamcrest-core-1.3.jar
   wget -nc https://repo1.maven.org/maven2/org/jmockit/jmockit/1.35/jmockit-1.35.jar -O $HOME/extension.lib.external/jmockit-1.35.jar
   wget -nc https://repo1.maven.org/maven2/org/jboss/logging/jboss-logging/3.3.0.Final/jboss-logging-3.3.0.Final.jar -O $HOME/extension.lib.external/jboss-logging-3.3.0.Final.jar
   wget -nc https://repo1.maven.org/maven2/org/glassfish/javax.el/3.0.1-b08/javax.el-3.0.1-b08.jar -O $HOME/extension.lib.external/javax.el-3.0.1-b08.jar
   wget -nc https://repo1.maven.org/maven2/com/fasterxml/classmate/1.3.1/classmate-1.3.1.jar -O $HOME/extension.lib.external/classmate-1.3.1.jar
   wget -nc https://repo1.maven.org/maven2/mysql/mysql-connector-java/5.1.48/mysql-connector-java-5.1.48.jar -O $HOME/extension.lib.external/mysql-connector-java-5.1.48.jar
   wget -nc https://archive.apache.org/dist/ant/binaries/apache-ant-1.10.7-bin.tar.gz -O $HOME/extension.lib.external/apache-ant-1.10.7-bin.tar.gz
   wget -nc https://archive.apache.org/dist/maven/ant-tasks/2.1.3/binaries/maven-ant-tasks-2.1.3.jar -O $HOME/extension.lib.external/mavenant/maven-ant-tasks-2.1.3.jar
   wget -nc https://download.jboss.org/wildfly/15.0.1.Final/wildfly-15.0.1.Final.tar.gz -O $HOME/extension.lib.external/wildfly-15.0.1.Final.tar.gz
   wget -nc https://download.eclipse.org/eclipse/downloads/drops4/R-4.10-201812060815/eclipse-SDK-4.10-linux-gtk-x86_64.tar.gz -O $HOME/extension.lib.external/eclipse-SDK-4.10-linux-gtk-x86_64.tar.gz
   wget -nc https://archive.apache.org/dist/maven/maven-3/3.6.0/binaries/apache-maven-3.6.0-bin.tar.gz -O $HOME/extension.lib.external/apache-maven-3.6.0-bin.tar.gz

   tar -x -z -C $HOME/extension.lib.external -f $HOME/extension.lib.external/wildfly-15.0.1.Final.tar.gz
   tar -x -z -C $HOME/extension.lib.external -f $HOME/extension.lib.external/eclipse-SDK-4.10-linux-gtk-x86_64.tar.gz
   tar -x -z -C $HOME/extension.lib.external -f $HOME/extension.lib.external/apache-maven-3.6.0-bin.tar.gz
}

if [[ ! -d $M2_HOME ]] ; then
   usage
   echo ""
   echo "M2_HOME is not set";
   exit 1;
fi

if [[ ! -d $ANT_HOME ]] ; then
   usage
   echo ""
   echo "ANT_HOME is not set";
   exit 1;
fi

MVN=$M2_HOME/bin/mvn
ANT=$ANT_HOME/bin/ant

if [[ -z $STAGE ]] ; then
   STAGE=$PWD/target/stagerepo
fi

if [[ -z $REPO ]] ; then
   REPO=$PWD/../Payara_PatchedProjects
fi

if [[ ! -d $REPO ]] ; then
   usage
   echo ""
   echo "Payara Patched projects is not present at $REPO. Set environment var REPO properly"
   exit 1
fi

RELEASE_VERSION=`grep release.version autobuild.properties | cut -d= -f2`
if [[ -z $PATCH_VERSION ]] ; then
   PATCH_VERSION=`grep $RELEASE_VERSION \
      $REPO/org/eclipse/persistence/org.eclipse.persistence.core/maven-metadata.xml | \
      tail -1 | \
      awk 'match($0,/p([0-9])/,x) { print x[1]+1 }'`
fi

if [[ -z $PATCH_VERSION ]] ; then
   PATCH_VERSION=0
fi

QUALIFIER="payara-p$PATCH_VERSION"
VERSION="$RELEASE_VERSION.$QUALIFIER"
echo "Will build $VERSION"

CMD=$1
case "$CMD" in
   compile)
      compile
      ;;
   install)
      install `localRepo`
      ;;

   deploy)
      rm -rf $STAGE/org/eclipse/persistence
      install $STAGE
      deploy
      ;;

   stage)
      rm -rf $STAGE/org/eclipse/persistence
      install $STAGE
      ;;      

   patch)
      patch $2
      ;;

   prepare)
      prepare
      ;;

   *)
      usage
      exit 1
      ;;
esac

      
   
