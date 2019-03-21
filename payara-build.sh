#!/bin/sh

usage() {
   echo "Usage"
   echo "\
Payara Eclipselink Build script
================================

Usage: $0 <command>

Where command is:
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
   $ANT -f antbuild.xml -Dversion.qualifier=$QUALIFIER clean clean-runtime build-src
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
   $MVN dependency:copy -Dartifact=org.apache.maven:maven-ant-tasks:2.0.8:jar -DoutputDirectory=target/
   $ANT -f uploadToMaven.xml -Dmavenant.dir=target/ -Drelease.version=$VERSION -Dbuild.type=RELEASE -Dgit.hash=`git rev-parse --short HEAD` -Dversion.string=$VERSION -Dmaven.repo.dir=$TARGET   
}

deploy() {
   rm pom.xml
   $MVN wagon:merge-maven-repos -Dwagon.source=file:/`realpath $STAGE` -Dwagon.target=file:/`realpath $REPO`
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
PATCH_VERSION=`grep $RELEASE_VERSION \
   $REPO/org/eclipse/persistence/org.eclipse.persistence.core/maven-metadata.xml | \
   tail -1 | \
   awk 'match($0,/p([0-9])/,x) { print x[1]+1 }'`

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
      break
      ;;
   install)
      install `localRepo`
      break
      ;;

   deploy)
      rm -rf $STAGE/org/eclipse/persistence
      install $STAGE
      deploy
      break
      ;;

   stage)
      rm -rf $STAGE/org/eclipse/persistence
      install $STAGE
      break
      ;;      

   patch)
      patch $2
      break
      ;;
   *)
      usage
      exit 1
      ;;
esac

      
   
