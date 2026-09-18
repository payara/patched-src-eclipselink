#!/bin/bash

usage() {
   echo "Usage"
   echo "\
Payara Eclipselink Build script
================================

Usage: $0 <command>

Where command is:
   prepare          Creates required direcory $HOME/extension.lib.external, downloads and unpacks required libs
   compile          Obviously
   install          To install compilation result to your maven repo (RELEASE version)
   snapshot-install To install a SNAPSHOT version to your local maven repo (adds -SNAPSHOT suffix, skips signing)
   snapshot         To deploy a SNAPSHOT version to the remote Nexus snapshot repository (requires Nexus credentials in settings.xml; set MAVEN_SETTINGS to override location)
   deploy           To deploy the compilation result into patched projects repo

The tool will determine the correct version automatically no furger arguments are needed (or implemented)

Environment:
   JAVA_HOME       must point to JDK 17+ (Tycho 3.0.4 requires class file version 61.0;
                   JDK 8 cannot load its classes and produces a cryptic Guice/P2 error)

   M2_HOME,
   ANT_HOME        pointing at respective tool installations

   MAVEN_SETTINGS  path to a custom Maven settings.xml (optional)
                   default: ~/.m2/settings.xml

   STAGE           location of staging repo (target/stagerepo by default)
   REPO            location of PatchedProjects repo (by default ../Payara_PatchedProjects)
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

# Publishes two special-case artifacts that the generic *_${VERSION}.jar loop misses:
#
#   1. org.eclipse.persistence.antlr
#      The ANTLR jar uses ANTLR's own OSGi version (e.g. antlr_3.5.3.v202311210849.jar),
#      not the EclipseLink build version. uploadToMaven.xml discovers the file via
#      <selectbundle> and publishes it under the EclipseLink maven.version. No extra
#      dependencies are needed (uploadToMaven.xml passes dependencies="").
#
#   2. org.eclipse.persistence.jpa.modelgen.processor
#      Same physical jar as jpa.modelgen but published under a different artifactId.
#      IMPORTANT: uploadToMaven.xml sets modelgen.dependencies = dep.core + dep.jpa,
#      meaning its published POM must declare org.eclipse.persistence.core and
#      org.eclipse.persistence.jpa as compile dependencies. Without these, Maven
#      annotation processor classpaths are missing AbstractSession at build time,
#      producing: NoClassDefFoundError: org/eclipse/persistence/internal/sessions/AbstractSession
#      We generate a proper POM with these dependencies rather than using -DgeneratePom=true
#      (which produces an empty-dependency POM and causes the above error).
#
# Arguments: $1 = MVN_GOAL ("install:install-file" or "deploy:deploy-file -Durl=... -DrepositoryId=...")
#            $2 = PLUGINS_DIR, $3 = MVN_VERSION
publish_special_artifacts() {
   local MVN_GOAL="$1"
   local PLUGINS_DIR="$2"
   local MVN_VERSION="$3"
   local GROUP="org.eclipse.persistence"

   # --- 1. ANTLR (no declared dependencies) ---
   local ANTLR_JAR
   ANTLR_JAR=$(ls "${PLUGINS_DIR}"/org.eclipse.persistence.antlr_*.jar 2>/dev/null | grep -v source | head -1)
   local ANTLR_SRC
   ANTLR_SRC=$(ls "${PLUGINS_DIR}"/org.eclipse.persistence.antlr*.source_*.jar 2>/dev/null | head -1)

   if [[ -f "$ANTLR_JAR" ]]; then
      echo "  [special] ${GROUP}:org.eclipse.persistence.antlr:${MVN_VERSION}"
      $MVN ${MVN_GOAL} \
         -Dfile="$ANTLR_JAR" \
         -DgroupId="$GROUP" \
         -DartifactId="org.eclipse.persistence.antlr" \
         -Dversion="$MVN_VERSION" \
         -Dpackaging=jar \
         -DgeneratePom=true \
         -q
      if [[ -f "$ANTLR_SRC" ]]; then
         $MVN ${MVN_GOAL} \
            -Dfile="$ANTLR_SRC" \
            -DgroupId="$GROUP" \
            -DartifactId="org.eclipse.persistence.antlr" \
            -Dversion="$MVN_VERSION" \
            -Dpackaging=jar \
            -Dclassifier=sources \
            -DgeneratePom=false \
            -q
      fi
   else
      echo "  [special] WARNING: org.eclipse.persistence.antlr jar not found in ${PLUGINS_DIR}"
   fi

   # --- 2. jpa.modelgen.processor (same jar as jpa.modelgen, different artifactId) ---
   # Requires a hand-crafted POM declaring core + jpa as compile dependencies so that
   # annotation processor classpaths include AbstractSession at consumer build time.
   local MODELGEN_JAR="${PLUGINS_DIR}/org.eclipse.persistence.jpa.modelgen_${VERSION}.jar"
   local MODELGEN_SRC="${PLUGINS_DIR}/org.eclipse.persistence.jpa.modelgen.source_${VERSION}.jar"

   if [[ -f "$MODELGEN_JAR" ]]; then
      echo "  [special] ${GROUP}:org.eclipse.persistence.jpa.modelgen.processor:${MVN_VERSION}"

      local MODELGEN_POM
      MODELGEN_POM=$(mktemp /tmp/modelgen-processor-pom.XXXXXX.xml)
      cat > "$MODELGEN_POM" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.eclipse.persistence</groupId>
  <artifactId>org.eclipse.persistence.jpa.modelgen.processor</artifactId>
  <version>${MVN_VERSION}</version>
  <packaging>jar</packaging>
  <name>EclipseLink JPA Modelgen (non-OSGi)</name>
  <dependencies>
    <dependency>
      <groupId>org.eclipse.persistence</groupId>
      <artifactId>org.eclipse.persistence.core</artifactId>
      <version>${MVN_VERSION}</version>
    </dependency>
    <dependency>
      <groupId>org.eclipse.persistence</groupId>
      <artifactId>org.eclipse.persistence.jpa</artifactId>
      <version>${MVN_VERSION}</version>
    </dependency>
  </dependencies>
</project>
EOF

      $MVN ${MVN_GOAL} \
         -Dfile="$MODELGEN_JAR" \
         -DpomFile="$MODELGEN_POM" \
         -q
      rm -f "$MODELGEN_POM"

      if [[ -f "$MODELGEN_SRC" ]]; then
         $MVN ${MVN_GOAL} \
            -Dfile="$MODELGEN_SRC" \
            -DgroupId="$GROUP" \
            -DartifactId="org.eclipse.persistence.jpa.modelgen.processor" \
            -Dversion="$MVN_VERSION" \
            -Dpackaging=jar \
            -Dclassifier=sources \
            -DgeneratePom=false \
            -q
      fi
   else
      echo "  [special] WARNING: org.eclipse.persistence.jpa.modelgen jar not found in ${PLUGINS_DIR}"
   fi
}

# Installs a SNAPSHOT version to the local Maven repository (~/.m2).
# Produces version: ${release.version}.payara-p${PATCH_VERSION}-SNAPSHOT
# e.g. 2.7.16.payara-p2-SNAPSHOT
#
# Uses mvn install:install-file directly instead of uploadToMaven.xml + maven-ant-tasks,
# because maven-ant-tasks-2.0.8 is incompatible with Ant 1.10+ / Java 11+ and produces:
#   "Target 'from' does not exist in the project 'Upload2Maven'"
snapshot-install() {
   local MVN_VERSION="${VERSION}-SNAPSHOT"
   local PLUGINS_DIR="$PWD/plugins"
   local GROUP="org.eclipse.persistence"

   echo "Installing EclipseLink ${MVN_VERSION} to local Maven repository..."

   local INSTALLED=0
   local SKIPPED=0

   # Iterate over every versioned jar in plugins/ for this build.
   # Filename format: {artifactId}_{version}.jar
   # Source format:   {artifactId}.source_{version}.jar  (skipped here, attached below)
   for JAR in "${PLUGINS_DIR}"/*_${VERSION}.jar; do
      [[ -f "$JAR" ]] || continue
      local BASENAME
      BASENAME=$(basename "$JAR")
      local ART="${BASENAME%_${VERSION}.jar}"

      # Skip the OSGi source bundles — they are installed as classifier=sources below
      [[ "$ART" == *.source ]] && continue

      local SRC="${PLUGINS_DIR}/${ART}.source_${VERSION}.jar"

      echo "  [install] ${GROUP}:${ART}:${MVN_VERSION}"
      $MVN install:install-file \
         -Dfile="$JAR" \
         -DgroupId="$GROUP" \
         -DartifactId="$ART" \
         -Dversion="$MVN_VERSION" \
         -Dpackaging=jar \
         -DgeneratePom=true \
         -q && INSTALLED=$((INSTALLED + 1)) || SKIPPED=$((SKIPPED + 1))

      # Attach sources jar if it exists
      if [[ -f "$SRC" ]]; then
         $MVN install:install-file \
            -Dfile="$SRC" \
            -DgroupId="$GROUP" \
            -DartifactId="$ART" \
            -Dversion="$MVN_VERSION" \
            -Dpackaging=jar \
            -Dclassifier=sources \
            -DgeneratePom=false \
            -q
      fi
   done

   # Publish special-case artifacts not covered by the generic loop
   publish_special_artifacts "install:install-file" "$PLUGINS_DIR" "$MVN_VERSION"

   echo ""
   echo "Done. Installed ${INSTALLED} artifact(s) as ${MVN_VERSION} (${SKIPPED} failed)."
   echo "Verify: find ~/.m2/repository/org/eclipse/persistence -name \"*${MVN_VERSION}*\" | sort"
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

upload() {
   ant -f uploadToNexus.xml -Dmavenant.dir=target/ -Drelease.version=${VERSION} -Dbuild.type=RELEASE -Dgit.hash=`git rev-parse --short HEAD` -Dversion.string=${VERSION} -Dmaven.repo.dir=$HOME/.m2/repository -Dmaven.repo.url=https://nexus.dev.payara.fish/repository/payara-artifacts -DstagingId=payara-artifacts -DstagingURL=https://nexus.dev.payara.fish/repository/payara-artifacts -Dasm.version=${VERSION}
}

# Deploys a SNAPSHOT version to the remote Nexus snapshot repository.
# Produces version: ${release.version}.payara-p${PATCH_VERSION}-SNAPSHOT
# e.g. 2.7.16.payara-p2-SNAPSHOT
#
# Prerequisites:
#   ~/.m2/settings.xml must contain a <server> entry with id "payara-nexus-snapshots"
#   and valid Nexus credentials.
#
# Replaces the original ant -f uploadToNexus.xml call because:
#   1. uploadToNexus.xml hardcoded build.type=RELEASE, so is.snapshot.build was never
#      set and no deploy targets ran.
#   2. maven-ant-tasks-2.1.3 (used by uploadToNexus.xml for typedef) is incompatible
#      with Ant 1.10+ / Java 11+, causing "Target 'from' does not exist" errors.
#   uploadToNexus.xml itself calls 'mvn deploy:deploy-file' via <exec> — this function
#   does the same without the broken Ant wrapper.
snapshot() {
   local MVN_VERSION="${VERSION}-SNAPSHOT"
   local PLUGINS_DIR="$PWD/plugins"
   local GROUP="org.eclipse.persistence"
   local NEXUS_URL="https://nexus.dev.payara.fish/repository/payara-snapshots"
   local NEXUS_ID="payara-nexus-snapshots"

   echo "Deploying EclipseLink ${MVN_VERSION} to Nexus snapshot repository..."
   echo "  URL: ${NEXUS_URL}"
   echo "  Credentials: repositoryId '${NEXUS_ID}' from ~/.m2/settings.xml"
   echo ""

   local DEPLOYED=0
   local FAILED=0

   for JAR in "${PLUGINS_DIR}"/*_${VERSION}.jar; do
      [[ -f "$JAR" ]] || continue
      local BASENAME
      BASENAME=$(basename "$JAR")
      local ART="${BASENAME%_${VERSION}.jar}"
      [[ "$ART" == *.source ]] && continue

      local SRC="${PLUGINS_DIR}/${ART}.source_${VERSION}.jar"

      echo "  [deploy] ${GROUP}:${ART}:${MVN_VERSION}"
      $MVN deploy:deploy-file \
         -Dfile="$JAR" \
         -DgroupId="$GROUP" \
         -DartifactId="$ART" \
         -Dversion="$MVN_VERSION" \
         -Dpackaging=jar \
         -DgeneratePom=true \
         -Durl="$NEXUS_URL" \
         -DrepositoryId="$NEXUS_ID" \
         -q && DEPLOYED=$((DEPLOYED + 1)) || FAILED=$((FAILED + 1))

      if [[ -f "$SRC" ]]; then
         $MVN deploy:deploy-file \
            -Dfile="$SRC" \
            -DgroupId="$GROUP" \
            -DartifactId="$ART" \
            -Dversion="$MVN_VERSION" \
            -Dpackaging=jar \
            -Dclassifier=sources \
            -DgeneratePom=false \
            -Durl="$NEXUS_URL" \
            -DrepositoryId="$NEXUS_ID" \
            -q
      fi
   done

   # Deploy special-case artifacts not covered by the generic loop
   publish_special_artifacts \
      "deploy:deploy-file -Durl=${NEXUS_URL} -DrepositoryId=${NEXUS_ID}" \
      "$PLUGINS_DIR" \
      "$MVN_VERSION"

   echo ""
   echo "Done. Deployed ${DEPLOYED} artifact(s) as ${MVN_VERSION} to Nexus (${FAILED} failed)."
   [[ $FAILED -gt 0 ]] && echo "  Tip: verify your settings.xml (${MAVEN_SETTINGS:-~/.m2/settings.xml}) has a <server id=\"${NEXUS_ID}\"> with valid credentials. Set MAVEN_SETTINGS to point at a custom settings file."
}

# Tycho 3.0.4 (used by the Maven sub-build spawned from Ant) requires Java 17+.
# Its class files are compiled at level 61.0; Java 8 (level 52.0) cannot load them.
# Fail early with a clear message rather than a cryptic Guice/Tycho class-loading error.
JAVA_MAJOR=$(java -version 2>&1 | head -1 | awk -F'"' '{print $2}' | awk -F'.' '{if ($1=="1") print $2; else print $1}')
if [[ -z "$JAVA_MAJOR" || "$JAVA_MAJOR" -lt 17 ]] ; then
   echo "ERROR: Java 17 or higher is required (Tycho 3.0.4 uses class file version 61.0)."
   echo "       Detected Java version: ${JAVA_MAJOR:-unknown}"
   echo "       Set JAVA_HOME or use 'sdk use java <17-version>' to switch."
   exit 1
fi

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

# Resolve Maven settings file. Use MAVEN_SETTINGS env var if set; otherwise default.
if [[ -n $MAVEN_SETTINGS ]] ; then
   if [[ ! -f $MAVEN_SETTINGS ]] ; then
      echo "ERROR: MAVEN_SETTINGS file not found: $MAVEN_SETTINGS"
      exit 1
   fi
   MVN="$MVN -s $MAVEN_SETTINGS"
   echo "Using Maven settings: $MAVEN_SETTINGS"
fi

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

   snapshot-install)
      snapshot-install
      ;;

   snapshot)
      snapshot
      ;;

   upload)
      upload
      ;;

   *)
      usage
      exit 1
      ;;
esac

      
   
