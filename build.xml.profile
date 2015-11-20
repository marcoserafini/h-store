<?xml version="1.0" ?>
<!DOCTYPE project [
       <!ENTITY common SYSTEM "build-common.xml">
]>
<project default="default" name="H-Store">

<!-- GENERAL HELPER MACROS -->

<macrodef name="envdefault">
    <attribute name="prop" />
    <attribute name="var" />
    <attribute name="default" />
    <sequential>
        <condition property="@{prop}" value="${env.@{var}}" else="@{default}">
            <isset property="env.@{var}" />
        </condition>
    </sequential>
</macrodef>

<!-- PATHS AND PROPERTIES -->

<!-- make environment var foo available as env.foo -->
<property environment="env"/>

<!-- allow env.VOLTBUILD to override "build" property -->
<envdefault prop="build" var="VOLTBUILD" default="release" />

<!-- <property name='build.dir'                   location='obj/${build}' /> -->
<property name='output.dir'                  location='obj' />
<property name='build.dir'                   location='${output.dir}/release' />
<property name='build.prod.dir'              location='${build.dir}/prod' />
<property name='build.benchmarks.dir'        location='${build.dir}/benchmarks' />
<property name='build.test.dir'              location='${build.dir}/test' />
<property name='build.preprocessor.dir'      location='${build.dir}/preprocessor' />
<property name='dist.dir'                    location='${build.dir}/dist' />
<property name='dist.examples.dir'           location='${dist.dir}/examples' />
<property name='dist.examples.auction.dir'   location='${dist.examples.dir}/auction' />
<property name='dist.examples.satellite.dir' location='${dist.examples.dir}/satellite' />
<property name='dist.examples.voter.dir'     location='${dist.examples.dir}/voter' />
<property name='properties.dir'              location='properties' />
<property name='benchmark.dir'               location='${properties.dir}/benchmarks' />
<property name='doc.dir'                     location='doc' />
<property name='src.dir'                     location='src' />
<property name='src.frontend.dir'            location='${src.dir}/frontend' />
<property name='src.benchmarks.dir'          location='${src.dir}/benchmarks' />
<property name='src.hsqldb.dir'              location='${src.dir}/hsqldb19b3' />
<property name='src.protorpc.dir'            location='${src.dir}/protorpc' />
<property name='src.test.dir'                location='tests/frontend' />
<property name='src.hsqldb.test.dir'         location='tests/hsqldb' />
<property name='src.ee.parent.dir'           location='src/ee' />
<property name='src.ee.dir'                  location='src/ee' />
<property name='tools.dir'                   location='tools' />
<property name='build.testoutput.dir'        location='${build.dir}/testoutput' />
<property name='build.testobjects.dir'       location='${build.dir}/testobjects' />
<property name='build.protorpc.dir'          location='${build.dir}/protorpc' />
<property name='thirdpartylib.dir'              location='third_party/java/jars' />
<property name='thirdpartydynlib.dir'              location='third_party/java/lib' />
<property name='thirdpartysrc.dir'              location='third_party/java/src'  />
<property name='m1catalog'                   location='${src.test.dir}/org/voltdb/catalog/catalog.txt' />
<property name='depcache'                    value='.depcache' />

<!-- Supplmental H-Store Files -->
<property name='files.dir'                   location='files' />
<property name='files.stats.dir'             location='${files.dir}/stats' />
<property name='files.workloads.dir'         location='${files.dir}/workloads' />
<property name='files.markovs.dir'           location='${files.dir}/markovs' />
<property name='files.hints.dir'             location='${files.dir}/designhints' />

<!-- BerkeleyDB Source -->
<property name="src.berkeleydb.dir"          location="third_party/cpp/berkeleydb" />
<property name="build.berkeleydb.dir"        location="${build.dir}/berkeleydb" />

<!-- ProtocolBuffer Source -->
<property name="src.protobuf.dir"            location="third_party/cpp/protobuf" />
<property name="build.protobuf.dir"          location="${build.dir}/protobuf" />
<property name="build.protobuf.protoc.dir"   location="${build.protobuf.dir}/src/protoc" />

<!-- emma build instrumentation location -->
<property name='build.instr.dir'             location='${build.dir}/instr' />

<!-- JProfiler Parameters -->
<property name='jprofiler.dir'               location='/home/ubuntu/Programs/jprofiler' />
<property name='jprofiler.port'              value='8849' />

<taskdef resource="net/sf/antcontrib/antcontrib.properties">
    <classpath>
        <pathelement location="${thirdpartylib.dir}/ant-contrib.jar"/>
    </classpath>
</taskdef>

<!--
******************************************************************************
** H-STORE CONFIGURATION
******************************************************************************
-->

<!-- Default H-Store Configuration File  -->
<condition property="conf" value="${properties.dir}/default.properties">
    <not><isset property="conf"/></not>
</condition>
<property file="${conf}"/>

<!-- IMPORTANT: We always need to set global.defaulthost if the user doesn't -->
<condition property="global.defaulthost" value="localhost">
    <not><isset property="global.defaulthost"/></not>
</condition>

<!-- SITE ASSERTS -->
<condition property="site.jvm_asserts" value="true">
    <not><isset property="site.jvm_asserts"/></not>
</condition>
<if>
    <equals arg1="${site.jvm_asserts}" arg2="true" />
    <then>
        <assertions id="site.assertions"><enable /></assertions>
    </then>
    <else>
        <assertions id="site.assertions"><disable /></assertions>
    </else>
</if>

<!-- CLIENT ASSERTS -->
<condition property="client.jvm_asserts" value="true">
    <not><isset property="client.jvm_asserts"/></not>
</condition>
<if>
    <equals arg1="${client.jvm_asserts}" arg2="true" />
    <then>
        <assertions id="client.assertions"><enable /></assertions>
    </then>
    <else>
        <assertions id="client.assertions"><disable /></assertions>
    </else>
</if>

<!--
******************************************************************************
** BENCHMARK CONFIGURATION
******************************************************************************
-->

<!-- Default Project -->
<!--<condition property="project" value="tpcc">
    <not><isset property="project"/></not>
</condition>-->

<!-- Project Jar File -->
<property name="benchmark.jar" location="${project}.jar" />
<condition property="jar" value="${benchmark.jar}">
    <not><isset property="jar"/></not>
</condition>

<!--
******************************************************************************
** ADDITIONAL CONFIGURATION
******************************************************************************
-->

<!-- Default heap size for utility programs (MB)  -->
<condition property="global.memory" value="2048">
    <not><isset property="global.memory"/></not>
</condition>

<!-- Overridden in the Hudson test script. -->
<property name='junit.haltonfailure'    value='false' />
<property name="j2se_api" value="http://java.sun.com/javase/6/docs/api/"/>

<path id='project.classpath'>
    <pathelement location='${build.instr.dir}' />
    <pathelement location='${build.prod.dir}' />
    <pathelement location='${build.benchmarks.dir}' />
    <pathelement location='${build.test.dir}' />
    <fileset dir='${thirdpartylib.dir}'>
        <include name='*.jar' />
        <exclude name='ant.jar' />
    </fileset>
    <pathelement path="${java.class.path}"/>
</path>

<!-- select which set of regression suite configuration types to run -->
<condition property="regressions" value="${regressions}" else="all">
  <isset property="regressions"/>
</condition>

<!-- Workload Tracer Properties -->
<condition property="workload.trace.class" value="">
    <not><isset property="workload.trace.class"/></not>
</condition>
<condition property="workload.trace.path" value="">
    <not><isset property="workload.trace.path"/></not>
</condition>
<condition property="workload.trace.ignore" value="">
    <not><isset property="workload.trace.ignore"/></not>
</condition>

<!--
******************************************************************************
PRIMARY ENTRY POINTS
******************************************************************************
-->

<target name="build"
    depends="default"
    description="Compile Java classes and C++ JNI library."
/>
<target name="build-java"
    depends="protorpc, compile"
    description="Compile Java classes."
/>
<target name="build-cpp"
    depends="ee"
    description="Compile C++ JNI library."
/>
<target name="default"
    depends="protorpc, compile, ee"
    description="Compile Java classes and C++ JNI library."
/>
<target name="check"
    depends="compile, voltdbipc, eecheck, junit, pythonfser"
    description="Run Java and C++ JNI testcases and test plan fragments."
/>
<target name="check_quick"
    depends="compile, voltdbipc, junit"
    description="Run a subset of Java testcases and test fragments."
/>
<!-- "junit, eecheck, doc, eedoc, jars" -->
<target name="build-all"
    depends="protobuf.java, protorpc, compile, ee"
    description="Do all tasks."
/>
<target name="jars"
    depends="hstore.jar"
    description="Create production JAR files."
/>
<target name="dist"
    depends="dist_internal"
    description="Create VoltDB release package with examples and documentation."
/>

<target name="checkstyle">
    <checkstyle config="checkstyle.xml">
        <fileset dir='${src.frontend.dir}'>
            <include name='**/*.java' />
            <exclude name='org/voltdb/network/*.java' />
        </fileset>
        <fileset dir='${src.test.dir}'>
            <include name='**/*.java' />
        </fileset>
        <formatter type="plain"/>
    </checkstyle>
</target>


<!--
******************************************************************************
ENVIRONMENT SETUP
******************************************************************************
-->

<target name="getcpus"
        description="Get the number of CPUs on this machine"
        unless="numcpus">
    <exec executable="${tools.dir}/getcpus.py" outputproperty="numcpus" />
</target>

<target name="getjava"
        description="Get local Java version"
        unless="global.jvm_version">
    <exec executable="${tools.dir}/getjava.py" outputproperty="global.jvm_version" />
</target>

<!--<target name="default_properties.check">
   <available property="default_properties.exists" file="${propeties.dir}/default.properties" />
</target>

<target name="default_properties" depends="default_properties.check" unless="default_properties.exists">
    <property name="default.properties" location="log4j.properties"/>
    <symlink link="${build.prod.dir}" resource="${log4j.properties}" failonerror="false"/>
</target>-->


<!--
******************************************************************************
DISTRIBUTION
******************************************************************************
-->

<target name="dist_internal" depends="compile, ee, hstore.jar">
    <!-- prepare release directory for new content -->
    <delete includeemptydirs="true" failonerror='false'>
        <fileset dir="${dist.dir}" includes="**/*" />
    </delete>
    <mkdir dir="${dist.dir}" />

    <mkdir dir="${dist.dir}/doc" />
    <mkdir dir="${dist.dir}/tools" />
    <mkdir dir="${dist.dir}/voltdb" />

    <!-- populate selected server/compiler javadoc documentation -->
    <javadoc
        destdir="${dist.dir}/doc/procedure-api"
        Public="true"
        version="true"
        use="true"
        nodeprecated="true"
        Overview='${src.frontend.dir}/overview-public.html'
        Windowtitle='VoltDB Server APIs'>
        <link href="${j2se_api}"/>
        <classpath refid='project.classpath' />
        <fileset dir="." defaultexcludes="yes">
            <include name="src/frontend/org/voltdb/VoltTable.java" />
            <include name="src/frontend/org/voltdb/VoltTableRow.java" />
            <include name="src/frontend/org/voltdb/VoltProcedure.java" />
            <include name="src/frontend/org/voltdb/SQLStmt.java" />
            <include name="src/frontend/org/voltdb/VoltType.java" />
            <include name="src/frontend/org/voltdb/ProcInfo.java" />
        </fileset>
    </javadoc>

    <!-- populate selected client javadoc documentation -->
    <javadoc
        destdir="${dist.dir}/doc/java-client-api"
        access="protected"
        version="true"
        use="true"
        nodeprecated="true"
        Overview='${src.frontend.dir}/overview-public.html'
        Windowtitle='VoltDB Client APIs'>
        <link href="${j2se_api}"/>
        <classpath refid='project.classpath' />
        <fileset dir="." defaultexcludes="yes">
            <include name="src/frontend/org/voltdb/VoltTable.java" />
            <include name="src/frontend/org/voltdb/VoltTableRow.java" />
            <include name="src/frontend/org/voltdb/VoltClient.java" />
            <include name="src/frontend/org/voltdb/VoltType.java" />
            <include name="src/frontend/org/voltdb/client/Client.java" />
            <include name="src/frontend/org/voltdb/client/NoConnectionsException.java" />
            <include name="src/frontend/org/voltdb/client/ProcedureCallback.java" />
            <include name="src/frontend/org/voltdb/client/ClientFactory.java" />
            <include name="src/frontend/org/voltdb/client/SyncCallback.java" />
            <include name="src/frontend/org/voltdb/client/NullCallback.java" />
            <include name="src/frontend/org/voltdb/client/ProcCallException.java" />
            <include name="src/frontend/org/voltdb/client/ClientStatusListener.java" />
            <include name="src/frontend/org/voltdb/client/ClientResponse.java" />
        </fileset>
    </javadoc>

    <!-- populate java and native libraries -->
    <copy todir="${dist.dir}/voltdb" flatten="true" >
        <fileset dir="${build.dir}" defaultexcludes="yes">
            <include name="prod/voltdb-${dist.version}.jar" />
            <include name="nativelibs/libvoltdb*" />
        </fileset>
    </copy>

    <!-- populate top level README by copying and renaming user_guide -->
    <copy tofile="${dist.dir}/README" file="doc/user_guide"/>

    <!-- populate examples top level README -->
    <copy tofile="${dist.dir}/examples/README" file="examples/README"/>

    <!-- populate the other examples -->
    <copy todir="${dist.dir}/examples" >
        <fileset dir="examples" defaultexcludes="yes">
            <include name="**/*"/>
        </fileset>
    </copy>

    <!-- populate the ad hoc tool -->
    <copy todir="${dist.dir}/tools" >
        <fileset dir="tools" defaultexcludes="yes">
            <include name="browser_adhoc/**"/>
        </fileset>
    </copy>

    <!-- copy fastserializer.py for python examples -->
    <copy todir="${dist.dir}/tools/browser_adhoc" file="tests/scripts/fastserializer.py"/>
    <copy todir="${dist.dir}/tools/browser_adhoc" file="tests/scripts/Query.py"/>

    <!-- populate project generator -->
    <exec dir="src/proj_gen/" executable="python">
        <arg line="generator_compiler.py"/>
    </exec>
    <move tofile="${dist.dir}/tools/generate" file="src/proj_gen/generate" />
    <chmod perm="ugo+rx">
        <fileset dir="${dist.dir}/tools" defaultexcludes="yes">
            <include name="generate"/>
        </fileset>
    </chmod>

    <!-- copy licenses -->
    <copy todir="${dist.dir}" file="COPYING"/>
    <copy todir="${dist.dir}/voltdb" file="COPYING"/>

    <!-- create an archive for distribution -->
    <tar destfile="${build.dir}/voltdb-temp.tar" >
        <tarfileset
            prefix="voltdb-${dist.version}"
            dir="${dist.dir}"
            includes="**/*"
            excludes="**/*.py generate"
        />
        <tarfileset
            prefix="voltdb-${dist.version}"
            dir="${dist.dir}"
            includes="**/*.py generate"
        />
    </tar>
    <gzip src="${build.dir}/voltdb-temp.tar" destfile="${build.dir}/voltdb-${dist.version}.tar.gz" />
    <delete file="${build.dir}/voltdb-temp.tar" />
</target>

<!--
******************************************************************************
CLEANING
******************************************************************************
-->

<target name="clean" depends="clean-all" />

<target name="clean-java">
    <delete includeemptydirs="true" dir="${build.prod.dir}" />
    <delete includeemptydirs="true" dir="${build.benchmarks.dir}" />
    <delete includeemptydirs="true" dir="${build.test.dir}" />
</target>

<target name="clean-cpp">
    <delete includeemptydirs="true" dir="${build.dir}/nativelibs" />
    <delete includeemptydirs="true" dir="${build.dir}/static_objects" />
    <delete includeemptydirs="true" dir="${build.dir}/objects" />
    <delete includeemptydirs="true" dir="${build.dir}/cpptests" />
    <delete includeemptydirs="true" dir="${build.testobjects.dir}" />
</target>

<target name="clean-all" description="Remove all compiled files.">
  <exec dir='.' executable='/bin/sh'>
    <arg line="-c 'rm -rf obj/*'"/>
  </exec>
</target>

<target name="clean-protorpc">
    <delete includeemptydirs="true" dir="${build.protorpc.dir}" />
</target>

<target name="clean-preprocessor">
    <delete includeemptydirs="true" dir="${build.preprocessor.dir}" />
</target>

<!--
******************************************************************************
JAR BUILDING
******************************************************************************
-->

<target name="buildinfo">
  <loadfile property='dist.version' srcFile='version.txt'>
      <filterchain><striplinebreaks/></filterchain>
  </loadfile>
  <exec dir="." executable="tools/getgitinfo.py">
      <arg line='${dist.version}' />
  </exec>
</target>

<target name="hstore.jar" depends="compile, buildinfo">
    <jar destfile="${build.prod.dir}/hstore.jar">
        <fileset dir="${build.prod.dir}" defaultexcludes="yes" >
            <include name="org/**" />
            <include name="edu/**" />
            <exclude name="edu/brown/gui/**" />
        </fileset>
        <fileset dir="${build.benchmarks.dir}" defaultexcludes="yes" >
            <include name="org/**" />
            <include name="edu/**" />
        </fileset>
        <fileset dir="${build.test.dir}" defaultexcludes="no" >
            <include name="org/voltdb/**" />
            <include name="edu/brown/**" />
            <include name="edu/mit/**" />
        </fileset>
        <fileset dir="${src.frontend.dir}" defaultexcludes="yes" >
            <include name="org/voltdb/**" />
            <include name="edu/brown/**" />
            <include name="edu/mit/**" />
        </fileset>
        <fileset dir="${src.benchmarks.dir}" defaultexcludes="yes" >
            <include name="org/voltdb/**" />
            <include name="edu/brown/**" />
            <include name="edu/mit/**" />
        </fileset>
        <fileset dir="${src.test.dir}" defaultexcludes="yes" >
            <include name="org/voltdb/**" />
            <include name="edu/brown/**" />
            <include name="edu/mit/**" />
        </fileset>
        <fileset dir="."><include name="buildstring.txt"/></fileset>
        <manifest>
            <section name="Credits">
                <attribute name="Author" value="H-Store" />
            </section>
            <section name="Shared">
                <attribute
                    name="Title"
                    value="VoltDB compiler, server, client and test libraries"
                />
                <attribute name="Date" value="${TODAY}" />
            </section>
        </manifest>
    </jar>
</target>

<!--
******************************************************************************
JAVA COMPILATION
******************************************************************************
-->

<target name="compile" depends="getjava">
    <mkdir dir='${build.prod.dir}' />
    <mkdir dir='${build.benchmarks.dir}' />
    <mkdir dir='${build.test.dir}' />
    <exec
        dir='${src.frontend.dir}/org/voltdb/utils'
        executable='${src.frontend.dir}/org/voltdb/utils/generate_logkeys.py'
        failonerror='true' />

<!--    <exec dir='.'
          executable='${tools.dir}/preprocessor.py'
          failonerror='true' >
        <arg line='${src.frontend.dir} ${build.preprocessor.dir}' />
    </exec>-->

    <depend
        srcdir="${src.hsqldb.dir}:${src.hsqldb.test.dir}:${src.frontend.dir}:${src.benchmarks.dir}:${src.test.dir}:${thirdpartysrc.dir}"
        destdir="${build.prod.dir}:${build.test.dir}"
        cache="${depcache}">
        <classpath refid="project.classpath" />
    </depend>

    <!-- copy resources needed for logging messages -->
    <copy todir="${build.prod.dir}">
        <fileset dir="${src.hsqldb.dir}" includes="**/*.properties" />
        <fileset dir="${src.frontend.dir}" includes="**/*.properties"/>
        <fileset dir="${src.frontend.dir}" includes="**/*.xml" />
    </copy>

    <copy todir='${build.prod.dir}/org/hsqldb/resources'>
        <fileset dir="${src.hsqldb.dir}/org/hsqldb/resources">
            <include name="*"/>
        </fileset>
    </copy>

    <!-- pick src//** schemas as package resources -->
    <copy flatten='false' todir="${build.prod.dir}">
        <fileset dir="${src.frontend.dir}">
            <include name="**/*.xsd"/>
        </fileset>
    </copy>

    <!-- the ddl files used by tests and benchmark clients are copied
         relative to the client class and found with class.getResource() -->
    <copy flatten='false' todir='${build.benchmarks.dir}'>
        <fileset dir="${src.benchmarks.dir}">
            <include name="**/*.sql"/>
            <include name="**/*.mappings"/>
        </fileset>
    </copy>
    <copy flatten='false' todir='${build.test.dir}'>
        <fileset dir="${src.test.dir}">
            <include name="**/*.sql"/>
            <include name="**/*.mappings"/>
        </fileset>
    </copy>

    <copy todir='${build.test.dir}/org/hsqldb'>
        <fileset dir="${src.hsqldb.test.dir}/org/hsqldb">
            <include name="*.sql"/>
        </fileset>
    </copy>

    <copy
        file='${src.test.dir}/org/voltdb/catalog/catalog.txt'
        todir='${build.test.dir}/org/voltdb/catalog'/>

    <!-- compile the individual source directories -->
    <javac includeantruntime="false"
        source="${global.jvm_version}"
        target="${global.jvm_version}"
        srcdir="${src.protorpc.dir}"
        destdir='${build.prod.dir}'
        debug='true'>
        <compilerarg line="-encoding utf-8"/>
        <classpath refid="project.classpath" />
    </javac>

    <javac includeantruntime="false"
        source="${global.jvm_version}"
        target="${global.jvm_version}"
        srcdir="${src.hsqldb.dir}"
        destdir='${build.prod.dir}'
        debug='true'>
        <compilerarg line="-encoding utf-8"/>
        <classpath refid="project.classpath" />
    </javac>

    <javac includeantruntime="false"
        source="${global.jvm_version}"
        target="${global.jvm_version}"
        srcdir="${thirdpartysrc.dir}"
        destdir='${build.prod.dir}'
        debug='true'>
        <compilerarg line="-encoding utf-8"/>
        <classpath refid="project.classpath" />
    </javac>

    <javac includeantruntime="false"
        source="${global.jvm_version}"
        target="${global.jvm_version}"
        srcdir="${src.frontend.dir}"
        destdir='${build.prod.dir}'
        debug='true'>
        <compilerarg line="-encoding utf-8"/>
        <classpath refid="project.classpath" />
    </javac>
    
    <javac includeantruntime="false"
        source="${global.jvm_version}"
        target="${global.jvm_version}"
        srcdir="${src.benchmarks.dir}"
        destdir='${build.benchmarks.dir}'
        debug='true'>
        <compilerarg line="-encoding utf-8"/>
        <classpath refid="project.classpath" />
    </javac>

    <!-- compile the individual test directories -->
    <javac includeantruntime="false"
        source="${global.jvm_version}"
        target="${global.jvm_version}"
        srcdir="${src.hsqldb.test.dir}"
        destdir='${build.test.dir}'
        debug='true'>
        <compilerarg line="-encoding utf-8"/>
        <classpath refid="project.classpath" />
    </javac>

    <javac includeantruntime="false"
        source="${global.jvm_version}"
        target="${global.jvm_version}"
        srcdir="${src.test.dir}"
        destdir='${build.test.dir}'
        excludes="org/voltdb/benchmark/tpcc/JDBCClient.java,edu/mit/elt/ELTTester**"
        debug='true'>
        <compilerarg line="-encoding utf-8"/>
        <classpath refid="project.classpath" />
    </javac>

<!--    <javac includeantruntime="false"
        target="${global.jvm_version}"
        srcdir="${build.preprocessor.dir}"
        destdir='${build.prod.dir}'
        debug='true'>
        <classpath refid="project.classpath" />
    </javac>-->

    <delete dir="${build.prod.dir}/META-INF/"/>

<!--     <antcall target="log4j_properties" /> -->
</target>

<!--<target name="log4j_properties.check">
   <available property="log4j_properties.exists" file="${build.prod.dir}/log4j.properties" />
</target>

<target name="log4j_properties" depends="log4j_properties.check" unless="log4j_properties.exists">
    <property name="log4j.properties" location="log4j.properties"/>
    <symlink link="${build.prod.dir}" resource="${log4j.properties}" failonerror="false"/>
</target>-->

<!--
******************************************************************************
DOCUMENTATION
******************************************************************************
-->

<target name="doc" description="Create Java doc files.">
    <javadoc
        destdir="${doc.dir}/java-api"
        version="true"
        use="true"
        Overview='${src.frontend.dir}/overview.html'>
        <classpath refid='project.classpath' />
        <link href="${j2se_api}"/>
        <fileset dir="." defaultexcludes="yes">
            <include name="src/frontend/org/voltdb/**/*.java"/>
        </fileset>
    </javadoc>
</target>

<taskdef
    name="doxygen"
    classname="org.doxygen.tools.DoxygenTask"
    classpath="${thirdpartylib.dir}/ant_doxygen.jar"
/>
<target name="eedoc"
    description="Generate doxygen C++ execution engine documentation.">
    <doxygen configfilename="${doc.dir}/Doxyfile" />
</target>

<!--
******************************************************************************
BERKELEYDB LIBRARY
******************************************************************************
-->

<target name="clean-berkeleydb">
    <delete includeemptydirs="true" dir="${build.berkeleydb.dir}" />
</target>

<target name="berkeleydb.check">
    <!-- Check whether we've already built the BerkeleyDB libraries -->
    <available property="berkeleydb.built"
               file="${build.berkeleydb.dir}/libdb_cxx.a" />
</target>

<target name="berkeleydb.compile" description="Builds the BerkeleyDB library"
                                depends="berkeleydb.check, getcpus"
                                unless="berkeleydb.built">
                                
    <mkdir dir="${build.berkeleydb.dir}" />
    <exec executable="${src.berkeleydb.dir}/dist/configure" 
          dir="${build.berkeleydb.dir}"
          failonerror="true"
          resolveexecutable="true">
          
        <arg value="--prefix=${build.berkeleydb.dir}" />
        <arg value="--enable-cxx" />
        <arg value="--enable-static" />
        <arg value="--enable-o_direct" />
        <arg value="--with-pic" />
        
        <!-- These are features that we don't need -->
        <arg value="--disable-atomicsupport" />
        <arg value="--disable-heap" />
        <arg value="--disable-queue" />
        <arg value="--disable-shared" />
        <arg value="--disable-test" />
        <arg value="--disable-cryptography" />
        <arg value="--disable-replication" />
        
    </exec>
    <exec executable="make" dir="${build.berkeleydb.dir}" failonerror="true">
        <arg value="-j${numcpus}" />
    </exec>
</target>

<!--
******************************************************************************
NATIVE EE STUFF
******************************************************************************
-->

<target name='jnicompile'
    depends='compile, jnicompile_temp, uptodate_jni_h.check'
    description="Build C++ JNI library."
    unless='uptodate_jni_h'>
    <delete file="${src.ee.dir}/org_voltdb_jni_ExecutionEngine.h" />
    <delete file="${src.ee.dir}/org_voltdb_utils_DBBPool.h" />
    <delete file="${src.ee.dir}/org_voltdb_utils_ThreadUtils.h" />
    <move
        file='${build.dir}/org_voltdb_jni_ExecutionEngine.h'
        todir='${src.ee.dir}'
    />
    <move
        file='${build.dir}/org_voltdb_utils_DBBPool.h'
        todir='${src.ee.dir}'
    />
    <move
        file='${build.dir}/org_voltdb_utils_ThreadUtils.h'
        todir='${src.ee.dir}'
    />
</target>

<target name='uptodate_jni_h.check' depends='jnicompile_temp'>
    <condition property='uptodate_jni_h'>
        <and>
            <filesmatch
                file1="${src.ee.dir}/org_voltdb_jni_ExecutionEngine.h"
                file2="${build.dir}/org_voltdb_jni_ExecutionEngine.h"
            />
            <filesmatch
                file1="${src.ee.dir}/org_voltdb_utils_DBBPool.h"
                file2="${build.dir}/org_voltdb_utils_DBBPool.h"
            />
            <filesmatch
                file1="${src.ee.dir}/org_voltdb_utils_ThreadUtils.h"
                file2="${build.dir}/org_voltdb_utils_ThreadUtils.h"
            />
        </and>
    </condition>
</target>

<target name='jnicompile_temp'>
    <delete file="${build.dir}/org_voltdb_jni_ExecutionEngine.h"/>
    <delete file="${build.dir}/org_voltdb_utils_DBBPool.h" />
    <javah
        classpathref="project.classpath"
        force="yes"
        verbose="yes"
        class="org.voltdb.jni.ExecutionEngine"
        destdir="${build.dir}"
    />
    <javah
        classpathref="project.classpath"
        force="yes"
        verbose="yes"
        class="org.voltdb.utils.DBBPool"
        destdir="${build.dir}"
    />
    <javah
        classpathref="project.classpath"
        force="yes"
        verbose="yes"
        class="org.voltdb.utils.ThreadUtils"
        destdir="${build.dir}"
    />
</target>

<target name="eecheck" depends="ee, eecheck-build"
    description="Build and execute testcases for C++ JNI library.">
    <exec dir='.' executable='python' failonerror='true'>
        <env key='M1CATALOG_PATH' value='${m1catalog}' />
        <env key="TEST_DIR" value="${build.testobjects.dir}" />
        <arg line="build.py ${build} test" />
    </exec>
</target>

<target name="eecheck-build"
    description="Quickly build the testcases for C++ JNI library.">
    <exec dir='.' executable='python' failonerror='true'>
        <env key='M1CATALOG_PATH' value='${m1catalog}' />
        <env key="TEST_DIR" value="${build.testobjects.dir}" />
        <arg line="build.py ${build} LOG_LEVEL=${site.exec_ee_log_level} buildtest" />
    </exec>
</target>

<target name='voltdbipc' depends="ee"
    description="Build the IPC client.">
    <exec dir='.' executable='python' failonerror='true'>
        <arg line="build.py ${build} voltdbipc" />
    </exec>
</target>

<target name='ee' depends="buildinfo, jnicompile, berkeleydb.compile, ee-build"
    description="Build C++ JNI library and copy it to production folder.">
<!--     <exec dir='.' executable='/bin/sh'>
        <arg line="-c 'rm -f ${build.dir}/nativelibs/libvoltdb.so'"/>
    </exec>
   <symlink link="${build.dir}/nativelibs/libvoltdb.so" resource="${build.dir}/nativelibs/libvoltdb-${dist.version}.so" overwrite="true" failonerror="false"/>-->
</target>
<target name="ee-build">
    <exec dir='.' executable='python' failonerror='true'>
        <arg value="build.py" />
        <arg value="LOG_LEVEL=${site.exec_ee_log_level}" />
        <arg value="MMAP_STORAGE=${site.storage_mmap}" />
        <arg value="ANTICACHE_BUILD=${site.anticache_build}" />
        <arg value="ANTICACHE_REVERSIBLE_LRU=${site.anticache_reversible_lru}" />
        <arg value="${build}" />
    </exec>
</target>

<target name='execplanfrag' depends="ee"
        description="Create test program that loads catalog and tables and executes a plan fragment.">
    <exec dir='.' executable='python' failonerror='true'>
        <arg line="build.py EXECPLANFRAG ${build}" />
    </exec>
</target>

<!--
******************************************************************************
TEST CASES
******************************************************************************
-->

<target name="pythonfser" description="run python fastserializer tests">
    <property name="build.dir.suffix" value="" /> <!-- Default -->
    <property name='classpath' refid='project.classpath' />
    <property name='echoserver.command' value="java
    -Djava.library.path=${build.dir}${build.dir.suffix}/nativelibs:${thirdpartydynlib.dir} -classpath
    ${classpath} -server -Xmx64m -XX:+AggressiveOpts -ea
    org.voltdb.messaging.EchoServer" />
    <exec dir='tests/scripts/' executable='python' failonerror='true'>
        <arg line="Testfastserializer.py"/>
        <arg line='"${echoserver.command}"'/>
    </exec>
</target>

<target name='with.emma' description="enable code coverage analysis" >
    <!-- set up emma -->
    <path id="emma.lib" >
        <pathelement location="${thirdpartylib.dir}/emma.jar" />
        <pathelement location="${thirdpartylib.dir}/emma_ant.jar" />
    </path>
    <taskdef resource="emma_ant.properties" classpathref="emma.lib" />
    <!-- enable emma -->
    <property name="emma.enabled" value="true" />
    <!-- instrument the code -->
    <property name="emma.dir" location="${build.dir}/emma" />
    <mkdir dir="${emma.dir}" />
    <emma>
        <!-- don't instrument build.test.dir or any non-voltdb code -->
        <instr
            instrpath="${build.prod.dir}/org/voltdb"
            destdir="${build.instr.dir}/org/voltdb"
            metadatafile="${emma.dir}/metadata.emma"
            merge="true"
        />
    </emma>
</target>

<!-- common junit parameters go here -->
<macrodef name='run_junit'>
    <attribute name='timeout' default='240000' />
    <attribute name='printsummary' default='off' />
    <attribute name='showoutput' default='false' />
    <element name='tests'/>
    <element name='formatters'/>

    <sequential>
        <mkdir dir='${build.testoutput.dir}' />
        <delete includeemptydirs="true" failonerror='false'>
            <fileset dir="${build.testobjects.dir}" includes="*.jar" />
        </delete>
        <junit
            fork="yes"
            haltonfailure="${junit.haltonfailure}"
            failureproperty="junit.failures"
            printsummary="@{printsummary}"
            timeout="@{timeout}"
            maxmemory='7600M'
            showoutput="@{showoutput}"
        >
            <classpath refid='project.classpath' />
            <jvmarg value="-Djava.library.path=${build.dir}/nativelibs:${thirdpartydynlib.dir}" />
            <jvmarg value="-server" />
            <jvmarg value="-Xcheck:jni" />
            <jvmarg value="-Xmx7600m"/>
            <jvmarg value="-XX:+HeapDumpOnOutOfMemoryError"/>

            <env key="ENABLE_JAR_REUSE" value="${junit.reusejars}"/>
            <env key="VOLTDB_BUILD_DIR" value="${build.dir}"/>
            <env key="TEST_DIR" value="${build.testobjects.dir}" />
            <env key="VOLT_REGRESSIONS" value="${regressions}" />
            <!-- Following two env vars are used by Java code
                 when running ant check -Dbuild=memcheck
                 The voltdbipc client is used in concert with valgrind
                 for most tests (those that would normally run against
                 the single process JNI backend. -->
            <env key="BUILD" value="${build}" />
            <env key="VOLTDBIPC_PATH" value="${build.prod.dir}/voltdbipc" />

            <!-- Brown Benchmark Parameters -->
<!--             <env key="TPCE_LOADER_FILES" value="${src.tpce.dir}" /> -->
            <env key="AIRLINE_DATA_DIR" value="${src.test.dir}/edu/brown/benchmark/airline/data" />

            <!-- code coverage output settings, harmless if not in use -->
            <jvmarg value="-Demma.coverage.out.file=${emma.dir}/coverage.emma" />
            <jvmarg value="-Demma.coverage.out.merge=true" />
            <!-- -->
            <formatters/>
            <batchtest todir="${build.testoutput.dir}">
                <tests/>
            </batchtest>
            <assertions><enable/></assertions>
        </junit>
    </sequential>
</macrodef>

<!-- A set of junit tests that only run on hudson and have an extended timeout -->
<!-- Run this before junit to pick up these tests in the reporting -->
<target name="junit-hudson">
    <run_junit timeout="900000" printsummary="yes">
        <formatters>
            <formatter type="xml" />
        </formatters>
        <tests>
            <fileset dir='${build.test.dir}'>
                <!-- currently empty! -->
            </fileset>
        </tests>
    </run_junit>
</target>

<target name="junit-getfiles.check">
    <available property="junit-getfiles.exists" file="${files.dir}" />
</target>

<!-- Fetch the files that we need to run the full tests -->
<target name="junit-getfiles"
        description="Fetch the files that we need to run junit-full"
        depends="junit-getfiles.check"
        unless="junit-getfiles.exists">
    <exec dir="${tools.dir}" executable="./getfiles.py">
        <arg line="--copy=${localcopy}" />
        <arg line="--symlink=${symlink}" />
        <arg line="${files.dir}" />
    </exec>
</target>
<target name="junit-getfiles-update"
        description="Update the files repository">
    <exec dir="${tools.dir}" executable="./getfiles.py">
        <arg line="--update" />
        <arg line="${files.dir}" />
    </exec>
</target>

<!-- Junit tests that run quickly -->
<target name="junit"
        description="Tests and suites that run in under 3 minutes under memcheck"
            depends="junit-getfiles">
    <run_junit>
        <formatters>
            <formatter type="plain" unless="hudson"/>
            <formatter
                type='xml'
                classname="org.voltdb.VoltJUnitFormatter"
                usefile='false'
                extension="none"
            />
            <formatter type="xml" />
        </formatters>
        <tests>
            <fileset dir='${build.test.dir}'>
                <include name='edu/brown/hstore/Test*.class'/>
                <include name='edu/brown/hstore/**/Test*.class'/>
                <include name='edu/brown/catalog/Test*.class'/>
                <include name='org/hsqldb/**/Test*.class'/>
                <include name='org/voltdb/**/Test*.class'/>
                <include name='org/voltdb/network/**/Test*.class'/>
                <include name='org/voltdb/messaging/**/*Test.class'/>
                <include name='org/voltdb/network/**/*Test.class'/>
                <include name='org/voltdb/utils/**/*Test.class'/>
                
                <!-- Busted tests because of hacking -->
                <exclude name='edu/brown/benchmark/airline/Test*'/>
                <exclude name='edu/brown/benchmark/airline/util/TestHistogramUtil*'/>

                <!-- VoltDB Regression Suite -->
                <exclude name="**/TestCatalogUpdateSuite.class" />
                <exclude name="**/TestFailureDetectSuite.class" />
                <exclude name="**/TestSneakyExecutionOrderSuite.class" />
                <exclude name="**/TestMaliciousClientSuite.class" />
                <exclude name="**/TestSaveRestoreSysprocSuite.class" />
                <exclude name="**/TestMapReduceTransactionSuite.class" />
                <exclude name="**/TestWikipediaSuite.class" />
                <exclude name="**/TestWikipediaLoader.class" />
                <exclude name="**/TestAntiCache*.class" /> 
                
                <!-- Misc VoltDB stuff -->
                <exclude name='org/voltdb/TestHSQLBackend*'/>
                <exclude name="**/*$*.class"/>
            </fileset>
        </tests>
    </run_junit>

    <!-- Generate unit test reports. -->
    <mkdir dir='${build.testoutput.dir}/report' />
    <junitreport todir="${build.testoutput.dir}">
        <fileset dir='${build.testoutput.dir}'>
            <include name="*.xml" />
            <exclude name='TESTS-TestSuites.xml' />
        </fileset>
        <report format="noframes" todir="${build.testoutput.dir}/report"/>
        <report
            styledir="tools"
            format="noframes"
            todir="${build.testoutput.dir}"
        />
    </junitreport>

    <!-- Fail the build if there were any problems.
        This runs all the tests before failing. -->
    <fail
        if="junit.failures"
        unless="emma.enabled"
        message="JUnit had failures"
    />
</target>

<target name="junit-full" description="Run testcases for Java classes.">
    <!-- Run the unit tests -->
    <condition property="timeoutLength" value="${timeoutLength}" else='300000'>
        <isset property="timeoutLength"/>
    </condition>

    <run_junit timeout="${timeoutLength}" printsummary="no">
        <formatters>
            <formatter type="plain" unless="hudson"/>
            <formatter
                type='xml'
                classname="org.voltdb.VoltJUnitFormatter"
                usefile='false'
                extension="none"
            />
            <formatter type="xml" />
        </formatters>
        <tests>
            <fileset dir='${build.test.dir}'>
                <exclude name="**/*$*.class"/>
                
                <!-- Brown Tests -->
                <include name='edu/brown/**/Test*.class'/>
                <include name='edu/brown/**/*Test.class'/>

                <!-- Canadian Tests -->
                <include name='ca/evanjones/**/*Test.class'/>
                <include name='edu/mit/**/*Test.class'/>
                <include name='edu/mit/**/Test*.class'/>

                <!-- VoltDB Tests -->
                <include name='org/hsqldb/**/Test*.class'/>
                <include name='org/voltdb/**/Test*.class'/>
                <include name='org/voltdb/network/**/Test*.class'/>
                <include name='org/voltdb/network/**/*Test.class'/>
                <include name='org/voltdb/messaging/**/*Test.class'/>
                <include name='org/voltdb/utils/**/*Test.class'/>
                
                <exclude name='org/voltdb/TestHSQLBackend*'/>
                
                <!-- VoltDB Regression Suite -->
                <exclude name="**/TestCatalogUpdateSuite.class" />
                <exclude name="**/TestFailureDetectSuite.class" />
                <exclude name="**/TestSneakyExecutionOrderSuite.class" />
                <exclude name="**/TestMaliciousClientSuite.class" />
                <exclude name="**/TestSaveRestoreSysprocSuite.class" />
                <exclude name="**/TestMapReduceTransactionSuite.class" />
                <exclude name="**/TestWikipediaSuite.class" />
                <exclude name="**/TestWikipediaLoader.class" />
                 <exclude name="**/TestAntiCache*.class" /> 

                <!-- Tests that use too much memory -->
                <exclude name='edu/brown/benchmark/tpce/*'/>
            </fileset>
        </tests>
    </run_junit>

    <!-- Generate unit test reports. -->
    <mkdir dir='${build.testoutput.dir}/report' />
    <junitreport todir="${build.testoutput.dir}">
        <fileset dir='${build.testoutput.dir}'>
            <include name="*.xml" />
            <exclude name='TESTS-TestSuites.xml' />
        </fileset>
        <report format="noframes" todir="${build.testoutput.dir}/report"/>
        <report
            styledir="tools"
            format="noframes"
            todir="${build.testoutput.dir}"
        />
    </junitreport>

    <exec dir="${build.testoutput.dir}" executable='cat'>
        <arg line="junit-noframes.html"/>
    </exec>
    <delete
        dir='${build.testoutput.dir}'
        includes='*.xml'
        excludes='TESTS-TestSuites.xml'
    />
    <!-- Fail the build if there were any problems.
        This runs all the tests before failing. -->
    <fail
        if="junit.failures"
        unless="emma.enabled"
        message="JUnit had failures"
    />

    <!-- Regenerate milestoneOneCatalog/ -->
<!--    <delete dir='${build.dir}/expanded/milestoneOneCatalog' />
    <mkdir dir='${build.dir}/expanded/milestoneOneCatalog' />
    <unjar
        src='${build.testobjects.dir}/milestoneOneCatalog.jar'
        dest='${build.dir}/expanded/milestoneOneCatalog'
    />-->
</target>

<target name="junit-regression" description="Run only regression testcases for Java classes.">
       <!-- Run the unit tests -->
       <condition property="timeoutLength" value="${timeoutLength}" else='300000'>
           <isset property="timeoutLength"/>
       </condition>
       
       <run_junit timeout="${timeoutLength}" printsummary="no">
           <formatters>
               <formatter type="plain" unless="hudson"/>
               <formatter
               type='xml'
               classname="org.voltdb.VoltJUnitFormatter"
               usefile='false'
               extension="none"
                   />
                <formatter type="xml" />
            </formatters>
            <tests>
                <fileset dir='${build.test.dir}'>
                    <include name='org/voltdb/regressionsuites/Test*.class'/>
                    <exclude name="**/*$*.class"/>
                    <exclude name="**/TestCatalogUpdateSuite.class" />
                    <exclude name="**/TestFailureDetectSuite.class" />
                    <exclude name="**/TestSneakyExecutionOrderSuite.class" />
                    <exclude name="**/TestMaliciousClientSuite.class" />
                    <exclude name="**/TestSaveRestoreSysprocSuite.class" />
                    <exclude name="**/TestMapReduceTransactionSuite.class" />
                    <exclude name="**/TestWikipediaSuite.class" />
                    <exclude name="**/TestWikipediaLoader.class" />
                     <exclude name="**/TestAntiCache*.class" /> 
                </fileset>
            </tests>
        </run_junit>
               
        <!-- Generate unit test reports. -->
        <mkdir dir='${build.testoutput.dir}/report' />
        <junitreport todir="${build.testoutput.dir}">
            <fileset dir='${build.testoutput.dir}'>
                <include name="*.xml" />
                <exclude name='TESTS-TestSuites.xml' />
            </fileset>
            <report format="noframes" todir="${build.testoutput.dir}/report"/>
            <report
                styledir="tools"
                format="noframes"
                todir="${build.testoutput.dir}"
                />
        </junitreport>
            
        <exec dir="${build.testoutput.dir}" executable='cat'>
            <arg line="junit-noframes.html"/>
        </exec>
        <delete
            dir='${build.testoutput.dir}'
            includes='*.xml'
            excludes='TESTS-TestSuites.xml'
        />
        <!-- Fail the build if there were any problems.
        This runs all the tests before failing. -->
        <fail
            if="junit.failures"
            unless="emma.enabled"
            message="JUnit had failures"
            />
</target>

<target
    name='emma-report'
    depends='with.emma, junit'
    description="Generate code coverage reports, if appropriate.">
    <emma>
        <report
            sourcepath="${src.frontend.dir}"
            sort="+name"
            metrics="method:70,block:80,line:80,class:100">
            <fileset dir="${emma.dir}"><include name="*.emma"/></fileset>
            <xml outfile="${emma.dir}/coverage.xml" depth="method" />
            <html
                outfile="${emma.dir}/coverage.html"
                depth="method"
                columns="name,class,method,block,line"
                encoding="UTF-8"
            />
        </report>
    </emma>
</target>

<!--
    this target is intended to be called only with antcall!
    set two properties beforehand or as part of the call:
    lcov.dir is the directory in which to put the coverage report
    lcov.target is the ant target to run under coverage
-->
<target name='with.lcov' description="Generate C++ code coverage reports.">
  <property name="lcov.base.tracefile" value="lcov_base.info" />
  <property name="lcov.test.tracefile" value="lcov_test.info" />
  <property name="lcov.tracefile" value="lcov.info" />
  <!-- Generate instrumented objects -->
  <!-- Whether any work needs doing is left to the C++ makefile -->
  <exec dir='.' executable='python' failonerror='true'>
    <arg line="build.py ${build} coverage" />
  </exec>
  <mkdir dir="${lcov.dir}" />
  <!-- Reset all counters -->
  <exec dir="${lcov.dir}" executable='lcov' failonerror="true">
    <arg line="--directory ${build.dir}-coverage/objects"/>
    <arg line="--zerocounters"/>
  </exec>
  <!-- Get baseline coverage (zero coverage) -->
  <exec dir="${lcov.dir}" executable='lcov' failonerror="true">
    <arg line="--directory ${build.dir}-coverage/objects"/>
    <arg line="-i --capture"/>
    <arg line="--output-file ${lcov.base.tracefile}"/>
    <arg line="-b ${src.ee.parent.dir}"/>
  </exec>
  <!-- Run the tests -->
  <antcall target="${lcov.target}">
    <param name="build.dir.suffix" value="-coverage" />
  </antcall>
  <!-- Get test coverage -->
  <exec dir="${lcov.dir}" executable='lcov' failonerror="true">
    <arg line="--directory ${build.dir}-coverage/objects"/>
    <arg line="--capture"/>
    <arg line="--output-file ${lcov.test.tracefile}"/>
    <arg line="-b ${src.ee.parent.dir}"/>
  </exec>
  <!-- Combine the baseline coverage and the test coverage -->
  <exec dir="${lcov.dir}" executable='lcov' failonerror="true">
    <arg line="-a ${lcov.base.tracefile}"/>
    <arg line="-a ${lcov.test.tracefile}"/>
    <arg line="-o ${lcov.tracefile}"/>
  </exec>
  <!-- Remove standard library and third party coverages -->
  <exec dir="${lcov.dir}" executable='lcov' failonerror="true">
    <arg line="-r ${lcov.tracefile}"/>
    <arg line='"/usr/include/*"'/>
    <arg line='"*third_party*"'/>
    <arg line="-o ${lcov.tracefile}"/>
  </exec>
  <!-- Generate HTML report -->
  <exec dir="${lcov.dir}" executable='genhtml' failonerror="true">
    <arg line="${lcov.tracefile}"/>
  </exec>
</target>

<target name='lcov-report' description=''>
    <property name="lcov.dir" location="${build.dir}-coverage/lcov" />
    <property name="lcov.target" value="sqlcoverage" />
    <antcall target="with.lcov" />
</target>

<target
    name='lcov-unit-tests'
    description="Run C++ unit tests from the coverage directory">
    <exec dir='.' executable='python' failonerror='true'>
        <env key='M1CATALOG_PATH' value='${m1catalog}' />
        <env key="TEST_DIR" value="${build.dir}-coverage/testobjects" />
        <arg line="build.py ${build} test coverage" />
    </exec>
</target>

<target
    name='lcov-unit-tests-report'
    description="Generate C++ unit test coverage reports.">
    <property
        name="lcov.dir"
        location="${build.dir}-coverage/lcov-unit-tests"
    />
    <property name="lcov.target" value="lcov-unit-tests" />
    <antcall target="with.lcov" />
</target>

<target
    name='testability-report'
    description="produce Google Code testability-explorer report">
    <path id="testability.lib">
        <pathelement
            location="${thirdpartylib.dir}/ant-testability-explorer.jar"
        />
        <pathelement
            location="${thirdpartylib.dir}/testability-explorer.jar"
        />
    </path>
    <taskdef
        name="testability"
        classname="com.google.ant.TestabilityTask"
        classpathref="testability.lib"
    />
    <testability
        resultfile="${build.dir}/testability.result.html" print="html"
        errorfile="${build.dir}/testability.err.txt">
        <classpath>
            <fileset dir="${build.prod.dir}">
                <include name="voltdbthin.jar" />
            </fileset>
        </classpath>
    </testability>
</target>

<target name="cpd">
    <taskdef
        name="cpdtask"
        classname="net.sourceforge.pmd.cpd.CPDTask"
        classpath="${thirdpartylib.dir}/pmd-4.2.5.jar"
    />
    <macrodef name="cpd">
        <attribute name="language"/>
        <attribute name="srcdir"/>
        <attribute name="format"/>
        <sequential>
            <echo>@{language} @{srcdir} @{format}</echo>
            <cpdtask
                minimumTokenCount="100"
                outputFile="${build.dir}/cpd-@{language}.@{format}"
                language="@{language}"
                format="@{format}">
                <fileset dir="@{srcdir}">
                    <include name="**/*.@{language}"/>
                </fileset>
            </cpdtask>
        </sequential>
    </macrodef>
    <cpd language="java" srcdir="${src.frontend.dir}" format="text"/>
    <cpd language="java" srcdir="${src.frontend.dir}" format="xml"/>
    <cpd language="cpp" srcdir="${src.ee.dir}" format="text"/>
    <cpd language="cpp" srcdir="${src.ee.dir}" format="xml"/>
</target>

<!-- This target will run a junit suite. It will also run a single
     suite under valgrind with -Dbuild=memcheck.  NOTE: to use valgrind,
     you must "cd obj/memcheck && make prod/voltdbipc" separately. -->
<target name='junitclass'
    description="Run one junit suite (i.e, -Djunitclass=TestSQLFeaturesSuite)">

    <condition property="timeoutLength" value="${timeoutLength}" else='300000'>
        <isset property="timeoutLength"/>
    </condition>
    <run_junit timeout="${timeoutLength}" printsummary="yes" showoutput="yes">
        <formatters>
            <formatter usefile="false" type="plain"/>
        </formatters>
        <tests>
            <fileset dir='${build.test.dir}'>
              <include name="**/${junitclass}.class"/>
            </fileset>
        </tests>
    </run_junit>
    <fail if="junit.failures" message="JUnit had failures" />
</target>

<target name='m1test_pre' depends='ee, compile'>
    <mkdir dir='${build.testoutput.dir}' />
    <junit fork="yes" printsummary='yes' haltonfailure="yes" showoutput='true'>
        <jvmarg value="-Djava.library.path=${build.dir}/nativelibs:${thirdpartydynlib.dir}" />
        <env key="TEST_DIR" value="${build.testobjects.dir}" />
        <env key="PLANNER" value="${planner}" />
        <classpath refid='project.classpath' />
        <formatter type="plain" unless="hudson"/>
        <formatter type="xml" if="hudson"/>
        <batchtest todir="${build.testoutput.dir}">
            <fileset dir='${build.test.dir}'>
                <include name='org/voltdb/compiler/TestMilestoneOneCompile.class'/>
                <exclude name="**/*$*.class"/>
            </fileset>
        </batchtest>
        <assertions><enable/></assertions>
    </junit>
    <delete dir='${build.dir}/expanded/milestoneOneCatalog' />
    <mkdir dir='${build.dir}/expanded/milestoneOneCatalog' />
    <unjar
        src='${build.testobjects.dir}/milestoneOneCatalog.jar'
        dest='${build.dir}/expanded/milestoneOneCatalog'
    />
    <copy
        file='${build.dir}/expanded/milestoneOneCatalog/catalog.txt'
        todir='${build.test.dir}/org/voltdb/catalog'
    />
    <copy
          file='${build.dir}/expanded/milestoneOneCatalog/catalog.txt'
          todir='${build.test.dir}/org/voltdb/catalog'
    />
    <copy
          file='${build.dir}/expanded/milestoneOneCatalog/catalog.txt'
          todir='${src.test.dir}/org/voltdb/catalog/'
    />
</target>

<target name='sqlcoverage' depends="ee,compile"
    description="Run the SQL coverage tests.">
    <property name="build.dir.suffix" value="" /> <!-- Default -->
    <property name="test.example.dir"
    location="tests/scripts/examples/sql_coverage" />
    <property name="default_config" location="${test.example.dir}/config.py" />
    <property name="regression_config"
              location="${test.example.dir}/regression-config.py" />
    <property name="sqlcov.dir" location="${build.dir}/sqlcoverage" />
    <exec dir='.' executable='/bin/sh'>
        <arg line="-c 'rm -rf ${sqlcov.dir}'"/>
    </exec>
    <mkdir dir="${sqlcov.dir}" />
    <condition property="" value="${env.VOLTBUILD}" else='release'>
        <isset property="env.VOLTBUILD"/>
    </condition>
    <condition property="seed_arg" value="-s ${sql_coverage_seed}" else="">
        <isset property="sql_coverage_seed"/>
    </condition>
    <condition property="meta_config" value="${default_config}"
               else="${regression_config}">
        <isset property="sql_coverage_default"/>
    </condition>
    <condition property="failonerror" value="true" else="false">
        <equals arg1="${meta_config}" arg2="${regression_config}" />
    </condition>
    <condition property="config_arg" value="-c ${sql_coverage_config}" else="">
        <isset property="sql_coverage_config"/>
    </condition>
    <condition property="config_verbose" value="-r" else="">
        <isset property="sql_coverage_verbose"/>
    </condition>
    <condition property="debug_output" value="" else="quietadhoc">
        <isset property="sql_coverage_verbose"/>
    </condition>
    <condition property="hosts" value="${sql_coverage_hosts}" else="1">
        <isset property="sql_coverage_hosts"/>
    </condition>
    <condition property="sitesperhost" value="${sql_coverage_sites}" else="1">
        <isset property="sql_coverage_sites"/>
    </condition>
    <condition property="replicas" value="${sql_coverage_replicas}" else="0">
        <isset property="sql_coverage_replicas"/>
    </condition>
    <property name='classpath' refid='project.classpath' />
    <property name='simpleserver.command' value='java
    -Djava.library.path=${build.dir}${build.dir.suffix}/nativelibs:${thirdpartydynlib.dir} -classpath
    ${classpath} -server -Xmx512m -XX:+AggressiveOpts -ea
    org.voltdb.sqlgenerator.SimpleServer hosts=${hosts}
    sitesperhost=${sitesperhost} replicas=${replicas}
    ${debug_output}' />
    <copy todir="${build.test.dir}/org/voltdb/sqlgenerator">
      <fileset dir="${test.example.dir}">
        <include name="**/*.sql"/>
      </fileset>
    </copy>
    <copy todir="tests/scripts">
      <fileset dir=".">
        <include name="buildstring.txt"/>
        <include name="version.txt"/>
      </fileset>
    </copy>
    <exec dir='tests/scripts' executable='python' failonerror="${failonerror}">
        <env key="TEST_DIR" value="${build.testobjects.dir}" />
        <env key="VOLTDB_BUILD_DIR" value="${build.dir}"/>
        <arg line='sql_coverage_test.py' />
        <arg line="${seed_arg}" />
        <arg line="${config_arg}" />
        <arg line="${config_verbose}" />
        <arg file="${meta_config}" />
        <arg file="${sqlcov.dir}" />
        <arg line='"${simpleserver.command}"' />
    </exec>
</target>

<target name='mock-site' description="EC2 Coordinator Tests">
    <java fork="yes" failonerror="true" classname="edu.brown.hstore.MockHStoreSite">
        <jvmarg value="-client" />
        <jvmarg value="-Xmx${client.memory}m" />
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <arg value="catalog.jar=${benchmark.jar}" />
        <arg value="${site}" />
        &common;
        <classpath refid='project.classpath' />
        <assertions>
            <enable/>
        </assertions>
    </java>
</target>


<!--
******************************************************************************
* PROTOCOL BUFFERS
******************************************************************************
-->

<!-- Common macro for compiling Java source -->
<macrodef name="ProtobufCompile">
    <attribute name="srcdir"/>
    <attribute name="destdir"/>
    <element name="compileoptions" implicit="true" optional="true"/>
    <sequential>
        <mkdir dir="@{destdir}"/>
        <!-- avoids needing ant clean when changing interfaces -->
        <depend srcdir="@{srcdir}" destdir="@{destdir}" cache="${depcache}"/>
        <javac includeantruntime="false"
            source="${global.jvm_version}"
            target="${global.jvm_version}"
            srcdir="@{srcdir}"
            destdir="@{destdir}"
            includeAntRuntime="no"
            debug="${compile.debug}">
            <compilerarg value="-Xlint:unchecked" />
            <!--<compilerarg value="-Xlint:deprecation" />-->
            <compileoptions/>
        </javac>
    </sequential>
</macrodef>

<!-- Generate Java protocol buffers. -->
<macrodef name="ProtobufGenerateJava">
    <attribute name="srcdir"/>
    <attribute name="destdir"/>
    <element name="files" implicit="true"/>

    <sequential>
        <mkdir dir="@{destdir}" />
        <apply executable="${build.protobuf.protoc.dir}" dest="@{destdir}" resolveexecutable="true" failonerror="true" verbose="true">
<!--         <apply executable="echo" dest="@{destdir}" resolveexecutable="true" failonerror="true" verbose="true"> -->
            <arg value="--java_out=@{destdir}" />
            <arg value="--proto_path=@{srcdir}" />
            <files/>
            <mapper type="glob" from="*.proto" to="*.java" />
        </apply>

        <!-- Delete any generated .java for .proto files which no longer exist. -->
        <!-- Does not work with the generated .java from Google's protocol buffers library... -->
        <!--<delete verbose="true"><fileset dir="@{destdir}">
            <not><present present="both" targetdir="@{srcdir}">
                <mapper type="glob" from="*.java" to="*.proto" />
            </present></not>
        </fileset></delete>-->
    </sequential>
</macrodef>

<target name="protobuf.protoc.check">
    <available property="protobuf.protoc.built" file="${build.protobuf.protoc.dir}" />
</target>

<target name="protobuf.compile" description="Builds the protocol buffer compiler"
                                depends="protobuf.protoc.check, getcpus, getjava"
                                unless="protobuf.protoc.built">
    <mkdir dir="${build.protobuf.dir}" />
    <exec executable="${src.protobuf.dir}/configure" dir="${build.protobuf.dir}" failonerror="true"  resolveexecutable="true">
        <arg value="--disable-shared" />
    </exec>
    <exec executable="make" dir="${build.protobuf.dir}" failonerror="true">
        <arg value="-j${numcpus}" />
    </exec>
</target>

<target name="protobuf.java.check">
    <uptodate property="protobuf.java.built"
              targetfile="${src.protorpc.dir}/protorpc/com/google/protobuf/DescriptorProtos.java">
        <srcfiles dir="${src.protobuf.dir}/java/src/main/java" includes="**/*.proto" />
    </uptodate>
</target>

<target name="protobuf.java" description="Generates require protocol buffer for Java"
                             depends="getjava, protobuf.compile, protobuf.java.check"
                             unless="protobuf.java.built" >
    <!-- Generate the required protocol buffers for the Java build -->
    <ProtobufGenerateJava srcdir="${src.protobuf.dir}/src" destdir="${src.protorpc.dir}">
        <fileset file="${src.protobuf.dir}/src/google/protobuf/descriptor.proto" />
    </ProtobufGenerateJava>
</target>

<target name="protorpc.java.check">
    <uptodate property="protorpc.built"
              targetfile="${src.protorpc.dir}/edu/brown/hstore/Hstore.java">
        <srcfiles dir="${src.protorpc.dir}" includes="**/*.proto" />
    </uptodate>
</target>

<target name="protorpc.java" description="Generates ProtoRPC files"
                        depends="getjava, protobuf.java, protorpc.java.check"
                        unless="protorpc.built">
    <ProtobufGenerateJava srcdir="${src.protorpc.dir}" destdir="${src.protorpc.dir}">
        <fileset dir="${src.protorpc.dir}">
            <patternset><include name="**/*.proto" /></patternset>
        </fileset>
    </ProtobufGenerateJava>
</target>

<target name="protorpc" description="" depends="getjava"> <!-- depends="protobuf.java,protobuf.compile">-->
    <mkdir dir='${build.prod.dir}' />
    <ProtobufCompile srcdir="${src.protobuf.dir}/java/src/main/java:${src.protorpc.dir}" destdir="${build.prod.dir}">
        <classpath refid="project.classpath"/>
    </ProtobufCompile>
</target>

<!--
******************************************************************************
* BENCHMARKS
******************************************************************************
-->

<target name='benchmark'
    description="Deploy a cluster and run a benchmark">

    <!-- Benchmarks Configuration File -->
    <condition property="benchmark.conf" value="${benchmark.dir}/${project}.properties">
        <not>
            <isset property="benchmark.conf"/>
        </not>
    </condition>
    <property file="${benchmark.conf}" prefix="benchmark" />
    <fail message="Invalid benchmark project '${project}'" unless="benchmark.builder" />

    <java fork="true" failonerror="true"
        classname="edu.brown.api.BenchmarkController">
        <classpath refid='project.classpath' />
        <jvmarg value="-server" />
        <jvmarg value="-Xmx2048m" />
        <jvmarg value="-Xcheck:jni"/>
        <jvmarg value="-Djava.library.path=${build.dir}/nativelibs:${thirdpartydynlib.dir}" />
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <!--<jvmarg value="-agentpath:/home/serafini/yjp-2015-build-15076/bin/linux-x86-64/libyjpagent.so=port=10001" />-->

        
        <assertions refid="site.assertions"/>

        <arg value="CONF=${conf}" />
        <arg value="CLIENTHEAP=${client.memory}" />
        <arg value="BACKEND=${backend}" />
        <arg value="HOSTCOUNT=${hostcount}" />
        <arg value="SITESPERHOST=${sitesperhost}"/>
        <arg value="KFACTOR=${kfactor}"/>
        <arg value="SSHOPTIONS=${global.sshoptions}" />
        <arg value="REMOTEPATH=${basedir}" />
        <arg value="REMOTEUSER=${remoteuser}"/>
        <arg value="CATALOG=${catalog}" />
        <arg value="DEFERRABLE=${deferrable}" />
        <arg value="EVICTABLE=${evictable}" />
        
        <arg value="partitionplan=${partitionplan}" />
        <arg value="partitionplan.nosecondary=${partitionplan.nosecondary}" />
        <arg value="partitionplan.ignore_missing=${partitionplan.ignore_missing}" />

        <arg value="markov=${markov}" />
        <arg value="markov.thresholds=${markov.thresholds}" />
        <arg value="markov.thresholds.value=${markov.thresholds.value}" />
        <arg value="markov.recompute_end=${markov.recompute_end}" />
        <arg value="markov.recompute_warmup=${markov.recompute_warmup}" />

        <!-- Execution Options -->
        <arg value="TRACE=${trace}" />

        <arg value="DUMPDATABASE=${client.dump_database}" />
        <arg value="DUMPDATABASEDIR=${client.dump_database_dir}" />

        &common;

        <!-- Optional sysproc -->
        <arg value="PROC=${proc}" />
        <arg value="PROCSTARTTIME=${proc_start_time}" />
        <arg value="PARAMS=${params}" />
        <arg value="PARAM0=${param0}" />
        <arg value="PARAM1=${param1}" />
        <arg value="PARAM2=${param2}" />
        <arg value="PARAM3=${param3}" />
	    <arg value="PARAM4=${param4}" />
        <arg value="PARAM5=${param5}" />
        <arg value="PARAM6=${param6}" />
        <arg value="PARAM7=${param7}" />
        <arg value="PARAM8=${param8}" />
        <arg value="PARAM9=${param9}" />

        <!-- Benchmark Parameters -->
        <arg value="benchmark.conf=${benchmark.conf}" />
        <arg value="benchmark.builder=${benchmark.builder}" />
        <arg value="benchmark.initial_polling_delay=${benchmark.initial_polling_delay}" />
        <arg value="benchmark.datadir=${benchmark.datadir}"/>
    	
        <!-- TPC-E Parameters -->
    	<arg value="benchmark.tpce_loader_files=${benchmark.tpce_loader_files}"/>
        <arg value="benchmark.tpce_total_customers=${benchmark.tpce_total_customers}"/>
        <arg value="benchmark.tpce_scale_factor=${benchmark.tpce_scale_factor}"/>
        <arg value="benchmark.tpce_initial_days=${benchmark.tpce_initial_days}"/>

        <!-- These are currently ignored (I think...) -->
        <arg value="HOST=${manualhost1}" />
        <arg value="HOST=${host1}" />
        <arg value="LISTENFORDEBUGGER=${debug}" />
        <arg value="USEPROFILE=${site.useprofile}" />
        <arg value="CHECKTRANSACTION=${checktransaction}" />
        <arg value="CHECKTABLES=${checktables}" />
        <arg value="LOCAL=${local}" />

        <arg value="COMPILE=${compile}" />
        <arg value="COMPILEONLY=${compileonly}" />
        <arg value="CATALOGHOSTS=${cataloghosts}" />
        <arg value="NOSITES=${nosites}" />
        <arg value="NOSTART=${nostart}" />
        <arg value="NOLOADER=${noloader}" />
        <arg value="NOUPLOADING=${nouploading}" />
        <arg value="NOEXECUTE=${noexecute}" />
        <arg value="NOSHUTDOWN=${noshutdown}" />
        <arg value="KILLONZERO=${killonzero}" />

        <!-- Actual number of connections opened to cluster will be:
            NUMCONNECTIONS * CLIENTCOUNT * PROCESSESPERCLIENT * HOSTCOUNT -->
        <arg value="NUMCONNECTIONS=${numconnections}" />
        <arg value="STATSDATABASETAG=${statsDatabaseTag}" />
        <arg value="STATSDATABASEURL=${statsDatabaseURL}" />
        <arg value="STATSDATABASEUSER=${statsDatabaseUser}" />
        <arg value="STATSDATABASEPASS=${statsDatabasePass}" />
        <arg value="STATSDATABASEJDBC=${statsDatabaseJDBC}" />

        <!-- measureoverhead parameters -->
        <arg value="transaction=${transaction}"/>

        <!-- miscellaneous reporting parameters -->
        <arg value="OS=${os}" />
    	
    	<!-- elasticity controller -->
        <arg value="elastic.monitoring_time=${elastic.monitoring_time}" />
        <arg value="elastic.min_load=${elastic.min_load}" />
        <arg value="elastic.max_load=${elastic.max_load}" />
        <arg value="elastic.imbalance_load=${elastic.imbalance_load}" />
        <arg value="elastic.dtxn_cost=${elastic.dtxn_cost}" />
        <arg value="elastic.lmpt_cost=${elastic.lmpt_cost}" />
        <arg value="elastic.max_tuples_move=${elastic.max_tuples_move}" />
        <arg value="elastic.min_gain_move=${elastic.min_gain_move}" />
        <arg value="elastic.max_partitions_added=${elastic.max_partitions_added}" />
        <arg value="elastic.run_monitoring=${elastic.run_monitoring}" />
        <arg value="elastic.update_plan=${elastic.update_plan}" />
        <arg value="elastic.exec_reconf=${elastic.exec_reconf}" />
        <arg value="elastic.delay=${elastic.delay}" />
        <arg value="elastic.plan_in=${elastic.plan_in}" />
        <arg value="elastic.plan_out=${elastic.plan_out}" />
        <arg value="elastic.algo=${elastic.algo}" />
        <arg value="elastic.topk=${elastic.topk}" />
        <arg value="elastic.root_table=${elastic.root_table}" />

    </java>
</target>

<target name='proccallmicrobench' depends='ee, compile'
    description="Run client-server stored procedure call overhead microbenchmark. [-Dclients={# clients}]">
    <java fork="true" failonerror="true"
        classname="org.voltdb.ProcedureCallMicrobench" >
        <arg value='${clients}' />
        <jvmarg value="-Djava.library.path=${build.dir}/nativelibs:${thirdpartydynlib.dir}" />
        <jvmarg value="-server" />
        <jvmarg value="-Xmx512m" />
        <classpath refid='project.classpath' />
        <assertions><disable /></assertions>
    </java>
</target>

<target name='update_logging' depends='compile'
    description="Invoke utility that connects to the specified VoltDB host and calls @UpdateLogging system procedure with the specified XML confiG file">
    <java fork="true" failonerror="true"
        classname="org.voltdb.UpdateLogging" >
        <arg value='host=${host}' />
        <arg value='config=${config}' />
        <arg value='allHosts=${allHosts}' />
        <arg value='user=${user}' />
        <arg value='password=${password}' />
        <classpath refid='project.classpath' />
        <assertions><enable /></assertions>
    </java>
</target>

<!--
******************************************************************************
* HUDSON-SPECIFIC TARGETS
******************************************************************************
-->

<target name='copy-coverage-files'
    description="Collect test results so that Hudson can display them even after an ant clean">
    <copy todir=".." preservelastmodified="true">
        <fileset dir="obj/release/emma" includes="coverage.html"/>
        <fileset dir="obj/release/emma" includes="_files/"/>
        <fileset dir="obj/release-coverage" includes="lcov-unit-tests"/>
        <fileset dir="${build.dir}" includes="testability.result.html"/>
    </copy>
</target>

<!--
******************************************************************************
UTILITIES
******************************************************************************
-->

<target name='dumper' description="Ask a running voltdb to dump state.">
    <java fork="true" classname="org.voltdb.utils.DumpManager">
        <jvmarg value="-server"/>
        <classpath refid="project.classpath"/>
        <arg value='${hostname}' />
    </java>
</target>

<target name='dumpcluster' description="Ask a running voltdb on the default cluster to dump state.">
    <java fork="true" classname="org.voltdb.utils.DumpManager">
        <jvmarg value="-server"/>
        <classpath refid="project.classpath"/>
        <arg value='volt3a' />
        <arg value='volt3b' />
        <arg value='volt3c' />
        <arg value='volt3d' />
        <arg value='volt3e' />
        <arg value='volt3f' />
    </java>
</target>

<target name="javaexec" description="Execute a Java class from the cmd line">
    <java fork="yes" classname="${javafile}">
        <jvmarg value="-Dlog4j.configuration=log4j.properties"/>
        <jvmarg value="-server" />
        <jvmarg value="-Xmx2048m" />
        <arg value="${javaargs}" />
        <classpath refid='project.classpath' />
        <assertions>
            <enable />
        </assertions>
   </java>
</target>

<!--
******************************************************************************
* CATALOG OPERATIONS
******************************************************************************
-->

<target name='catalog-fix'
        description="Updates the catalog in a JAR to include foreign keys and parameter mappings">
    <basename property="jar.filename" file="${jar}"/>
    <property name="jar.backup" location="${global.temp_dir}/backup/${jar.filename}" />
    <copy file="${jar}" tofile="${jar.backup}" />
    <unjar src="${jar}" dest="${global.temp_dir}/fixcatalog/"/>
    <java fork="yes" failonerror="true" classname="edu.brown.catalog.FixCatalog">
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <arg value="catalog.jar=${jar}" />
        <arg value="catalog.type=${type}" />
        <arg value="catalog.cluster=${cluster}" />
        <arg value="catalog.output=${global.temp_dir}/fixcatalog/catalog.txt" />
        <arg value="mappings=${mappings}" />
        <arg value="catalog.hosts=${hosts}" />
        <arg value="catalog.hosts.cores=${cores}" />
        <arg value="catalog.hosts.threads=${threads}" />
        <arg value="catalog.hosts.memory=${memory}" />
        <arg value="catalog.numhosts=${numhosts}" />
        <arg value="catalog.hosts.numsites=${numsites}" />
        <arg value="catalog.site.numpartitions=${numpartitions}" />
        <arg value="catalog.port=${port}" />
        <classpath refid='project.classpath' />
        <assertions><enable /></assertions>
    </java>
   <jar destfile="${jar}" basedir="${global.temp_dir}/fixcatalog/"/>
   <delete includeemptydirs="true" dir="${global.temp_dir}/fixcatalog" failonerror='false'/>
</target>

<target name='catalog-info'
        description="Print catalog host/partition information">
    <java fork="yes" failonerror="true" classname="edu.brown.catalog.CatalogInfo">
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <arg value="catalog.jar=${jar}" />
        <arg value="workload=${workload}" />
        <arg value="workload.removedupes=true" />
        <arg value="workload.xactlimit=${limit}" />
        <arg value="workload.xactoffset=${offset}" />
        <arg value="workload.sampling=${sampling}" />
        <arg value="workload.procexclude=${exclude}" />
        <arg value="workload.procinclude=${include}" />
        <arg value="workload.procinclude.multiplier=${multiplier}" />
        <classpath refid='project.classpath' />
        <assertions><enable /></assertions>
    </java>
</target>

<target name='catalog-export' description="Export catalog procs/stmts to JSON file">
    <condition property="output" value="${project}.json">
        <not><isset property="output"/></not>
    </condition>
    <java fork="yes" failonerror="true" classname="edu.brown.catalog.CatalogExporter">
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <arg value="catalog.jar=${jar}" />
        <arg value="catalog.output=${output}" />
        <classpath refid='project.classpath' />
        <assertions><enable /></assertions>
    </java>
    <echo>Wrote catalog file to ${output}</echo>
</target>

<target name='catalog-viewer' description="Catalog Viewer">
    <java fork="yes" classname="edu.brown.gui.CatalogViewer">
        <jvmarg value="-Xmx512m" />
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <arg value="catalog.jar=${jar}" />
        <arg value="catalog=${catalog}" />
        <arg value="partitionplan=${partitionplan}" />
        <arg value="partitionplan.apply=true" />
        <arg value="conflicts.exclude_procedures=${exclude}" />
        <classpath refid='project.classpath' />
        <assertions><enable /></assertions>
    </java>
</target>

<target name='graphviz' description="GraphViz Schema Export">
    <java fork="yes" classname="edu.brown.graphs.GraphvizExport">
        <jvmarg value="-Xmx512m" />
        <arg value="catalog.jar=${jar}" />
        <arg value="catalog.labels=${labels}" />
        <arg line="${ignore}" />
        <classpath refid='project.classpath' />
        <assertions>
            <enable />
        </assertions>
    </java>
    <!--    <exec dir="${basedir}" executable="dot">
       <arg line="-Tpng ${project}.dot > ${project}.png"/>
    </exec>-->
</target>

<target name='conflicts-export' description="Export ConflictGraphs as GraphViz File">
    <java fork="yes" classname="edu.brown.catalog.conflicts.ConflictGraphExport">
        <jvmarg value="-Xmx512m" />
        <arg value="catalog.jar=${jar}" />
        <arg value="conflicts.exclude_procedures=${excludeProcedures}" />
        <arg value="conflicts.exclude_statements=${excludeStatements}" />
        <arg value="conflicts.focus=${focus}" />
        <arg line="${output}" />
        <classpath refid='project.classpath' />
        <assertions>
            <enable />
        </assertions>
    </java>
    <!--    <exec dir="${basedir}" executable="dot">
    <arg line="-Tpng ${project}.dot > ${project}.png"/>
</exec>-->
</target>

<target name='conflicts-dump' description="Export table-based ConflictGraphs as GraphViz File">
    <java fork="yes" classname="edu.brown.catalog.conflicts.ConflictSetTableDumper">
        <jvmarg value="-Xmx512m" />
        <arg value="catalog.jar=${jar}" />
        <arg value="conflicts.exclude_procedures=${excludeProcs}" />
        <arg value="conflicts.exclude_statements=${excludeStmts}" />
        <arg value="conflicts.focus=${focus}" />
        <arg line="${output}" />
        <classpath refid='project.classpath' />
        <assertions>
            <enable />
        </assertions>
    </java>
</target>

<!--
******************************************************************************
* WORKLOAD OPERATIONS
******************************************************************************
-->

<target name='workload-fix'>
   <java fork="yes" failonerror="true" classname="edu.brown.workload.FixWorkload">
      <arg value="catalog.jar=${jar}" />
      <arg value="workload=${workload}" />
      <arg value="workload.xactlimit=${limit}" />
      <arg value="workload.output=${output}" />
      <arg value="${sigma}" />
      <classpath refid='project.classpath' />
      <assertions><enable /></assertions>
   </java>
</target>

<target name='workload-combine'>
    <java fork="yes" failonerror="true" classname="edu.brown.workload.CombineWorkloadTraces">
        <jvmarg value="-Xmx${global.memory}m" />
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <arg value="catalog.jar=${jar}" />
        <arg value="workload.output=${output}" />
        <arg value="${workload}" />
        <classpath refid='project.classpath' />
        <assertions><enable /></assertions>
    </java>
    <gzip src="${output}" destfile="${output}.gz" />
    <delete file="${output}" />
</target>

<target name='workload-compress'>
    <java fork="yes" failonerror="true" classname="edu.brown.workload.WorkloadSummarizer">
        <jvmarg value="-Xmx${global.memory}m" />
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <arg value="catalog.jar=${jar}" />
        <arg value="catalog.hosts=${hosts}" />
        <arg value="workload=${workload}" />
        <arg value="workload.output=${output}" />
        <arg value="workload.xactlimit=${limit}" />
        <arg value="workload.xactoffset=${offset}" />
        <arg value="workload.procexclude=${exclude}" />
        <arg value="workload.procinclude=${include}" />
        <arg value="workload.procinclude.multiplier=${multiplier}" />
        <arg value="mappings=${files.mappings.dir}/${project}.mappings" />
        <arg value="designer.intervals=${intervals}" />
        <classpath refid='project.classpath' />
        <assertions><enable /></assertions>
    </java>
    <gzip src="${output}" destfile="${output}.gz" />
    <delete file="${output}" />
</target>

<target name='workload-stats'
        description="Generate workload statistics">
    <java fork="yes" classname="edu.brown.statistics.WorkloadStatistics" failonerror='true'>
        <jvmarg value="-Xmx${global.memory}m" />
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <arg value="catalog.jar=${jar}" />
        <arg value="workload=${workload}" />
        <arg value="workload.xactlimit=${limit}" />
        <arg value="workload.xactoffset=${offset}" />
        <arg value="workload.procexclude=${exclude}" />
        <arg value="workload.procinclude=${include}" />
        <arg value="workload.procinclude.multiplier=${multiplier}" />
        <arg value="stats=${stats}" />
        <arg value="stats.output=${output}" />
        <classpath refid='project.classpath' />
        <assertions><enable /></assertions>
    </java>
</target>

<!--
******************************************************************************
* MARKOV MODELS
******************************************************************************
-->

<target name='markov-generate' description="Generate Markov Graphs" depends="getcpus">
    <!-- Benchmarks Configuration File -->
    <condition property="benchmark.conf" value="${benchmark.dir}/${project}.properties">
        <not>
            <isset property="benchmark.conf"/>
        </not>
    </condition>
    <property file="${benchmark.conf}" prefix="benchmark" />
    
    <condition property="exclude" value="${benchmark.workload.ignore}">
        <not><isset property="exclude"/></not>
    </condition>
    
    <java fork="yes" classname="edu.brown.markov.MarkovGraph" failonerror='true'>
        <jvmarg value="-Xmx${global.memory}m" />
        <jvmarg value="-Dhstore.max_threads=${numcpus}" />
        <arg value="catalog.jar=${jar}" />
        <arg value="workload=${workload}" />
        <arg value="workload.xactlimit=${limit}" />
        <arg value="workload.xactoffset=${offset}" />
        <arg value="workload.procexclude=${exclude}" />
        <arg value="workload.procinclude=${include}" />
        <arg value="workload.procinclude.multiplier=${multiplier}" />
        <arg value="markov=${markov}" />
        <arg value="markov.output=${output}" />
        <arg value="markov.global=${global}" />
        <classpath refid='project.classpath' />
        <assertions><enable /></assertions>
    </java>
</target>

<target name='markov-cost' description="Estimate MarkovGraph Costs" depends="getcpus">
    <java fork="yes" classname="edu.brown.costmodel.MarkovCostModel" failonerror='true'>
        <jvmarg value="-Xmx${global.memory}m" />
        <jvmarg value="-Dhstore.max_threads=${numcpus}" />
        <arg value="catalog.jar=${jar}" />
        <arg value="workload=${workload}" />
        <arg value="workload.xactlimit=${limit}" />
        <arg value="workload.xactoffset=${offset}" />
        <arg value="workload.procexclude=${exclude}" />
        <arg value="workload.procinclude=${include}" />
        <arg value="workload.procinclude.multiplier=${multiplier}" />
        <arg value="mappings=${files.mappings.dir}/${project}.mappings" />
        <arg value="markov=${markov}" />
        <arg value="markov.thresholds=${markov.thresholds}" />
        <arg value="markov.thresholds.value=${markov.thresholds.value}" />
        <classpath refid='project.classpath' />
        <assertions><enable /></assertions>
    </java>
</target>

<target name='markov-graphviz' description="Create MarkovGraphs Graphviz files">
    <java fork="yes" classname="edu.brown.markov.MarkovGraphvizExport" failonerror='true'>
        <jvmarg value="-Xmx${global.memory}m" />
        <arg value="catalog.jar=${jar}" />
        <arg value="markov=${markov}" />
        <arg value="${procedure}" />
        <arg value="${partition}" />
        <classpath refid='project.classpath' />
        <assertions><enable /></assertions>
    </java>
</target>

<target name='markov-extractor' description="Generate Weka Feature Files">
    <java fork="yes" classname="edu.brown.markov.FeatureExtractor" failonerror='true'>
        <jvmarg value="-Xmx${global.memory}m" />
        <arg value="catalog.jar=${jar}" />
        <arg value="workload=${workload}" />
        <arg value="workload.xactlimit=${limit}" />
        <arg value="workload.xactoffset=${offset}" />
        <arg value="workload.procexclude=${exclude}" />
        <arg value="workload.procinclude=${include}" />
        <arg value="workload.procinclude.multiplier=${multiplier}" />
        <arg value="mappings=${files.mappings.dir}/${project}.mappings" />
        <classpath refid='project.classpath' />
        <assertions><enable /></assertions>
    </java>
</target>

<target name='markov-cluster' description="Generate Markov Cluster-based File" depends="getcpus">
    <java fork="yes" classname="edu.brown.markov.FeatureClusterer" failonerror='true'>
        <jvmarg value="-Xmx${global.memory}m" />
        <jvmarg value="-server" />
<!--         <jvmarg value="-verbose:gc" /> -->
        <jvmarg value="-XX:+UseParNewGC" />
        <jvmarg value="-XX:+UseConcMarkSweepGC" />
<!--         <jvmarg value="-XX:+PrintGCDetails" /> -->
<!--         <jvmarg value="-XX:+PrintGCTimeStamps" /> -->
<!--         <jvmarg value="-Xloggc:gc.log" /> -->
        <arg value="catalog.jar=${jar}" />
        <arg value="workload=${workload}" />
        <arg value="workload.randompartitions=${randompartitions}" />
        <arg value="workload.xactlimit=${limit}" />
        <arg value="workload.xactoffset=${offset}" />
        <arg value="workload.procinclude=${procedure}" />
        <arg value="workload.procinclude.multiplier=${multiplier}" />
        <arg value="mappings=${files.mappings.dir}/${project}.mappings" />
        <arg value="markov.split.training=${training}" />
        <arg value="markov.split.validation=${validation}" />
        <arg value="markov.split.testing=${testing}" />
        <arg value="markov.rounds=${rounds}" />
        <arg value="markov.threads=${numcpus}" />
        <arg value="markov.partitions=${partitions}" />
        <arg value="markov.topk=${topk}" />
        <arg value="${procedure}" />
        <arg value="${procedure}.arff" />
        <classpath refid='project.classpath' />
        <assertions><enable /></assertions>
    </java>
</target>

<target name='weka' description="Launch Weka GUI">
    <java fork="yes" classname="weka.gui.GUIChooser">
        <jvmarg value="-Xmx${global.memory}m" />
        <classpath refid='project.classpath' />
        <!-- <assertions><enable /></assertions> -->
    </java>
</target>

<!--
******************************************************************************
* PARAMETER MAPPINGS
******************************************************************************
-->

<target name='mappings-generate'
        description="Generate Parameter Mappings">
    <java fork="yes" classname="edu.brown.mappings.MappingCalculator" failonerror='true'>
        <jvmarg value="-Xmx${global.memory}m" />
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <arg value="catalog.jar=${jar}" />
        <arg value="workload=${workload}" />
        <arg value="workload.xactlimit=${limit}" />
        <arg value="workload.xactoffset=${offset}" />
        <arg value="workload.procexclude=${exclude}" />
        <arg value="workload.procinclude=${include}" />
        <arg value="workload.procinclude.multiplier=${multiplier}" />
        <arg value="mappings.output=${output}" />
        <arg value="mappings.threshold=${threshold}" />
        <classpath refid='project.classpath' />
        <assertions><enable /></assertions>
    </java>
</target>

<!--
******************************************************************************
* AUTOMATIC DATABASE DESIGNER
******************************************************************************
-->

<target name='designer-prepare'
        description="Prepare various files needed by designer components">

    <condition property="target.workload" value="${project}">
        <not><isset property="target.workload"/></not>
    </condition>
    <property name="benchmark.tablestats" location="${global.temp_dir}/stats/${project}.stats" />

    <echo>Creating table stats file for ${project}...</echo>
    <java fork="yes" classname="edu.brown.statistics.AbstractTableStatisticsGenerator" failonerror='true'>
        <jvmarg value="-Xmx${global.memory}m" />
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <arg value="catalog.jar=${jar}" />
        <arg value="stats.scalefactor=${scalefactor}" />
        <arg value="stats.output=${benchmark.tablestats}" />
        <classpath refid='project.classpath' />
        <assertions><enable /></assertions>
    </java>

    <echo>Creating stats file for workload '${target.workload}' using '${benchmark.tablestats}' as base</echo>
    <antcall target="stats">
        <param name="workload" value="${files.workloads.dir}/${target.workload}.trace.gz" />
        <param name="stats" value="${benchmark.tablestats}" />
        <param name="output" value="${files.stats.dir}/${project}.stats" />
    </antcall>
    <gzip src="${files.stats.dir}/${project}.stats" destfile="${files.stats.dir}/${project}.stats.gz"/>
    <delete file="${files.stats.dir}/${project}.stats" />

    <echo>Creating mappings file for workload '${target.workload}'</echo>
    <antcall target="mappings-generate">
        <param name="workload" value="${files.workloads.dir}/${target.workload}.trace.gz" />
        <param name="output" value="${files.mappings.dir}/${project}.mappings" />
    </antcall>
</target>


<target name='designer-jar'
        description="Prepare a jar file for the designer!">
    <copy tofile="${project}-designer-benchmark.jar" file="${jar}"/>
    <antcall target="catalog-fix">
        <param name="jar" value="${project}-designer-benchmark.jar" />
        <param name="type" value="${project}" />
        <param name="mappings" value="${files.mappings.dir}/${project}.mappings" />
        <param name="numhosts" value="25" />
        <param name="numsites" value="2" />
        <param name="numpartitions" value="2" />
        <param name="catalog.site.numpartitions" value="partitionspersite" />
    </antcall>
    <antcall target="catalog-info">
        <param name="jar" value="${project}-designer-benchmark.jar" />
    </antcall>
</target>

<target name='designer-benchmark'
        description="Creates a new database designer for a catalog jar file">
   <java fork="yes" failonerror="true" classname="edu.brown.designer.Designer">
        <jvmarg value="-server" />
        <jvmarg value="-Xms256m" />
        <jvmarg value="-Xmx${global.memory}m" />
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <jvmarg value="-Dhstore.max_threads=${numcpus}" />
        <arg value="conf=${conf}" />
        <arg value="catalog.jar=${jar}" />
        <arg value="catalog.type=${type}" />
        <arg value="catalog.hosts=${hosts}" />
        <arg value="workload=${workload}" />
        <arg value="workload.removedupes=true" />
        <arg value="workload.xactlimit=${limit}" />
        <arg value="workload.xactoffset=${offset}" />
        <arg value="workload.sampling=${sampling}" />
        <arg value="workload.procexclude=${exclude}" />
        <arg value="workload.procinclude=${include}" />
        <arg value="workload.procinclude.multiplier=${multiplier}" />
        <arg value="mappings=${mappings}" />
        <arg value="stats=${stats}" />
        <arg value="stats.scalefactor=${scalefactor}" />
        <arg value="designer.partitioner=${partitioner}" />
        <arg value="designer.threads=${threads}" />
        <arg value="designer.costmodel=${costmodel}" />
        <arg value="designer.intervals=${intervals}" />
        <arg value="designer.hints=${hints}" />
        <arg value="designer.checkpoint=${checkpoint}" />
        <arg value="partitionplan.output=${output}" />
        <arg line="${extraparams}" />
        <classpath refid='project.classpath' />
        <assertions><enable/></assertions>
    </java>
</target>

<target name='designer-estimate'
        description="Estimate the cost of executing the workload for a given PartitionPlan">
    <condition property="costmodel" value="edu.brown.costmodel.SingleSitedCostModel">
        <not>
            <isset property="costmodel"/>
        </not>
    </condition>
    <java fork="yes" failonerror="true" classname="${costmodel}">
        <jvmarg value="-client" />
        <jvmarg value="-Xms256m" />
        <jvmarg value="-Xmx${global.memory}m" />
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <arg value="conf=${conf}" />
        <arg value="catalog.jar=${jar}" />
        <arg value="catalog.type=${project}" />
        <arg value="catalog.hosts=${hosts}" />
        <arg value="workload=${workload}" />
        <arg value="workload.removedupes=true" />
        <arg value="workload.xactlimit=${limit}" />
        <arg value="workload.xactoffset=${offset}" />
        <arg value="workload.sampling=${sampling}" />
        <arg value="workload.procexclude=${exclude}" />
        <arg value="workload.procinclude=${include}" />
        <arg value="workload.procinclude.multiplier=${multiplier}" />
        <arg value="designer.intervals=${intervals}" />
        <arg value="designer.hints=${hints}" />
        <arg value="partitionplan=${partitionplan}" />
        <arg value="partitionplan.removeprocs=${partitionplan.removeprocs}" />
        <arg value="partitionplan.randomprocs=${partitionplan.randomprocs}" />
        <arg line="${extraparams}" />
        <classpath refid='project.classpath' />
        <assertions><enable/></assertions>
    </java>
</target>

<target name='designer-lowerbounds'
        description="Calculate lower bounds">
   <java fork="yes" failonerror="true" classname="edu.brown.designer.LowerBoundsCalculator">
        <jvmarg value="-client" />
        <jvmarg value="-Xms256m" />
        <jvmarg value="-Xmx${global.memory}m" />
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <arg value="catalog.jar=${jar}" />
        <arg value="catalog.type=${project}" />
        <arg value="catalog.hosts=${hosts}" />
        <arg value="workload=${workload}" />
        <arg value="workload.removedupes=true" />
        <arg value="workload.xactlimit=${limit}" />
        <arg value="workload.xactoffset=${offset}" />
        <arg value="workload.sampling=${sampling}" />
        <arg value="workload.procexclude=${exclude}" />
        <arg value="workload.procinclude=${include}" />
        <arg value="workload.procinclude.multiplier=${multiplier}" />
        <arg value="mappings=${files.mappings.dir}/${project}.mappings" />
        <arg value="stats=${stats}" />
        <arg value="designer.partitioner=${partitioner}" />
        <arg value="designer.costmodel=${costmodel}" />
        <arg value="designer.intervals=${intervals}" />
        <arg value="designer.hints=${hints}" />
        <arg value="partitionplan=${partitionplan}" />
        <arg line="${extraparams}" />
        <classpath refid='project.classpath' />
        <assertions><enable/></assertions>
    </java>
</target>

<!--
******************************************************************************
* OLTP Generator
******************************************************************************
-->

<target name="oltpgen.clean">
        <delete includeemptydirs="true" dir="${build.test.dir}/edu/brown/oltpgenerator" />
</target>

<target name="oltpgen.compile" depends="getjava, oltpgen.clean, compile">
        <javac includeantruntime="false"
                source="${global.jvm_version}"
                target="${global.jvm_version}"
                srcdir="${src.test.dir}"
                destdir="${build.test.dir}"
                debug='true'>
                <classpath refid="project.classpath" />
        </javac>
</target>

<target name="oltpgen.main" description="OLTP Generator Main Gui">
        <java fork="yes" classname="edu.brown.oltpgenerator.gui.GuiMain">
                <arg value="catalog.jar=${src.test.dir}/edu/brown/oltpgenerator/tmp/tm1.jar" />
                <arg value="markov=${src.test.dir}/edu/brown/oltpgenerator/tmp/tm1.markov" />
                <classpath refid='project.classpath' />
        </java>
</target>

<target name="oltpgen.helloworld" description="Generate HelloWorld benchmark">
        <java fork="yes" classname="edu.brown.oltpgenerator.test.GenHelloWorldBenchmark">
                <arg value="catalog.jar=${src.test.dir}/edu/brown/oltpgenerator/tmp/tm1.jar" />
                <arg value="markov=${src.test.dir}/edu/brown/oltpgenerator/tmp/tm1.markov" />
                <classpath refid='project.classpath' />
        </java>
</target>

<!--
******************************************************************************
* H-STORE SYSTEM
******************************************************************************
-->

<target name="hstore-prepare" description="Prepares a benchmark catalog">
    <!-- Compile + create benchmark project jar -->
    <antcall target="benchmark">
        <param name="compileonly"   value="true" />
        <param name="compile"       value="true" />
        <param name="host1"         value="${global.defaulthost}" />
    </antcall>

    <!-- Sets up host/site/partition information in system catalogs -->
    <antcall target="hstore-jar" />
</target>

<target name="hstore-jar" description="H-Store JAR Setup">
    <antcall target="catalog-fix">
        <param name="jar" value="${benchmark.jar}" />
        <param name="mappings" value="${files.mappings.dir}/${project}.mappings" />
        
        <!-- FORMAT: <hostname>:<site#>:<partition#> -->
        <!-- Default is two sites, each with one partition -->
        <param name="hosts" value="${global.defaulthost}:0:0;${global.defaulthost}:1:1" />
    </antcall>
</target>

<target name='hstore-dist' description="Prepare Repository for New Release">
    <java fork="yes" failonerror="true" classname="edu.brown.hstore.conf.HStoreConfUtil">
        <jvmarg value="-Xmx${client.memory}m" />
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <arg value="${basedir}/build-common.xml" />
        <classpath refid='project.classpath' />
        <assertions>
            <enable/>
        </assertions>
    </java>
</target>

<target name="hstore-site" description="H-Store Execution Site">
    <condition property="markov" value="${files.markovs.dir}/${project}.markovs">
        <not><isset property="markov"/></not>
    </condition>

    <java fork="yes" classname="edu.brown.hstore.HStore">
        <!-- Use to identify what process this for killing -->
        <jvmarg value="-Dhstore.tag=site" />
        
        <!-- Initial Heap Size -->
        <jvmarg value="-Xms100m" />
        <!-- Maximum Heap Size -->
        <jvmarg value="-Xmx${site.memory}m" />

        <!-- JProfiler -->
        <!-- <jvmarg value="-agentpath:${jprofiler.dir}/bin/linux-x64/libjprofilerti.so=port=${jprofiler.port}" /> -->

        <!-- Parallel Garbage Collector (Slower) -->
        <!-- <jvmarg value="-XX:+UseParNewGC" /> -->
        <!-- <jvmarg value="-XX:+UseConcMarkSweepGC" /> -->
        <!-- <jvmarg value="-XX:+CMSIncrementalMode" /> -->

        <!-- Java 1.7 G1 Garbage Collector -->
        <jvmarg value="-XX:+UseG1GC" />
        <jvmarg value="-XX:+UseCompressedOops" />

        <!-- Garbage Collector Debug Output -->
        <!-- <jvmarg value="-verbose:gc" /> -->
        <!-- <jvmarg value="-XX:+PrintGCDetails" /> -->
        <!-- <jvmarg value="-XX:+PrintGCTimeStamps" /> -->
        <!-- <jvmarg value="-XX:-TraceClassUnloading" /> -->

        <!-- Other JVM Options -->
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <jvmarg value="-server" />
        <jvmarg value="-Djava.library.path=${build.dir}/nativelibs:${thirdpartydynlib.dir}" />
        <jvmarg value="-agentpath:/home/serafini/yjp-2015-build-15076/bin/linux-x86-64/libyjpagent.so=port=10001" />

        <arg value="catalog.jar=${jar}" />
        <arg value="conf=${conf}" />
        <arg value="site.id=${site.id}" />
        <arg value="hasher.class=${hasher}" />
        <arg value="markov=${markov}" />
        <arg value="markov.thresholds=${markov.thresholds}" />
        <arg value="markov.thresholds.value=${markov.thresholds.value}" />
        <arg value="mappings=${mappings}" />
        <arg value="partitionplan=${partitionplan}" />
        <arg value="partitionplan.apply=true" />
        <arg value="partitionplan.nosecondary=${partitionplan.nosecondary}" />
        <arg value="partitionplan.ignore_missing=${partitionplan.ignore_missing}" />
        <arg value="workload.output=${workload.output}" />
        <arg value="workload.procexclude=${benchmark.workload.ignore}" />

        &common;

        <classpath refid='project.classpath' />
        <assertions refid="site.assertions"/>
    </java>
</target>

<target name='hstore-benchmark' description="H-Store Benchmark">
    <antcall target="benchmark">
        <param name="catalog" value="${jar}" />
        <param name="compile" value="false" />
        <param name="cataloghosts" value="true" />
    </antcall>
</target>

<target name='hstore-start' description="Start the H-Store cluster">
    <condition property="noloader" value="true">
        <not><isset property="noloader"/></not>
    </condition>
    <antcall target="hstore-benchmark">
        <param name="noloader" value="${noloader}" />
        <param name="noexecute" value="true" />
        <param name="noshutdown" value="true" />
    </antcall>
</target>

<target name='hstore-invoke'
        description="Execute on transaction on an already running H-Store cluster">
    <java fork="yes" failonerror="true" classname="edu.brown.hstore.VoltProcedureInvoker">
        <jvmarg value="-client" />
        <jvmarg value="-Xms256m" />
        <jvmarg value="-Xmx${client.memory}m" />
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <arg value="catalog.jar=${benchmark.jar}" />
        <arg value="${proc}" />
        <arg value="${params}" />
        <arg value="${param0}" />
        <arg value="${param1}" />
        <arg value="${param2}" />
        <arg value="${param3}" />
        <arg value="${param4}" />
        <arg value="${param5}" />
        <arg value="${param6}" />
        <arg value="${param7}" />
        <arg value="${param8}" />
        <arg value="${param9}" />
        <classpath refid='project.classpath' />
        <assertions><enable/></assertions>
    </java>
</target>

<target name='hstore-term'
        description="H-Store Commandline Terminal">
    <java fork="yes" failonerror="true" classname="edu.brown.hstore.HStoreTerminal">
        <jvmarg value="-client" />
        <jvmarg value="-Xms256m" />
        <jvmarg value="-Xmx${client.memory}m" />
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <arg value="catalog.jar=${benchmark.jar}" />
        <classpath refid='project.classpath' />
        <assertions><enable/></assertions>
    </java>
</target>
	
<target name='elastic-controller'
        description="H-Store Elasticity Controller">
  <java fork="yes" failonerror="true" classname="org.qcri.PartitioningPlanner.Controller">
        <jvmarg value="-client" />
        <jvmarg value="-Xms256m" />
        <jvmarg value="-Xmx${client.memory}m" />
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <jvmarg value="-Djava.library.path=${build.dir}/nativelibs:${thirdpartydynlib.dir}" />
        <arg value="catalog.jar=${benchmark.jar}" />
        <arg value="${numPart}" />
        <arg value="${tWindow}" />
        <arg value="${plannerID}" />
        <arg value="${provisioning}" />
  	<arg value="${timeLimit}" />
  	<arg value="${monitoring}" />
  	<arg value="${sitesPerHost}" />
  	<arg value="${partPerSite}" />
  	<arg value="${highCPU}" />
  	<arg value="${lowCPU}" />
	&common;
        <classpath refid='project.classpath' />
    <assertions><enable/></assertions>
    </java>
</target>

<target name='affinity'
        description="H-Store Affinity Controller">
  <java fork="yes" failonerror="true" classname="org.qcri.affinityplanner.Controller">
        <jvmarg value="-client" />
        <jvmarg value="-Xms256m" />
        <jvmarg value="-Xmx${client.memory}m" />
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <jvmarg value="-Djava.library.path=${build.dir}/nativelibs:${thirdpartydynlib.dir}" />
        <arg value="catalog.jar=${benchmark.jar}" />
	<arg value="elastic.monitoring_time=${elastic.monitoring_time}" />
	<arg value="elastic.min_load=${elastic.min_load}" />
    	<arg value="elastic.max_load=${elastic.max_load}" />
    	<arg value="elastic.imbalance_load=${elastic.imbalance_load}" />
    	<arg value="elastic.dtxn_cost=${elastic.dtxn_cost}" />
    	<arg value="elastic.lmpt_cost=${elastic.lmpt_cost}" />
    	<arg value="elastic.max_tuples_move=${elastic.max_tuples_move}" />
    	<arg value="elastic.min_gain_move=${elastic.min_gain_move}" />
    	<arg value="elastic.max_partitions_added=${elastic.max_partitions_added}" />
	<arg value="elastic.run_monitoring=${elastic.run_monitoring}" />
	<arg value="elastic.update_plan=${elastic.update_plan}" />
	<arg value="elastic.exec_reconf=${elastic.exec_reconf}" />
	<arg value="elastic.plan_in=${elastic.plan_in}" />
	<arg value="elastic.plan_out=${elastic.plan_out}" />
        <arg value="elastic.algo=${elastic.algo}" />
        <arg value="elastic.topk=${elastic.topk}" />
    	<arg value="elastic.root_table=${elastic.root_table}" />
	&common;
        <classpath refid='project.classpath' />
    <assertions><enable/></assertions>
    </java>
</target>

<target name='affinity-profile'
        description="H-Store Affinity Controller with Profiler Agent">
  <java fork="yes" failonerror="true" classname="org.qcri.affinityplanner.Controller">
        <jvmarg value="-client" />
        <jvmarg value="-agentpath:/home/mserafini/yjp-2014-build-14120/bin/linux-x86-64/libyjpagent.so=port=10001" />
        <jvmarg value="-Xms256m" />
        <jvmarg value="-Xmx${client.memory}m" />
        <jvmarg value="-Dlog4j.configuration=${basedir}/log4j.properties"/>
        <jvmarg value="-Djava.library.path=${build.dir}/nativelibs:${thirdpartydynlib.dir}" />
        <arg value="catalog.jar=${benchmark.jar}" />
	<arg value="elastic.monitoring_time=${elastic.monitoring_time}" />
	<arg value="elastic.min_load=${elastic.min_load}" />
    	<arg value="elastic.max_load=${elastic.max_load}" />
    	<arg value="elastic.imbalance_load=${elastic.imbalance_load}" />
    	<arg value="elastic.dtxn_cost=${elastic.dtxn_cost}" />
    	<arg value="elastic.lmpt_cost=${elastic.lmpt_cost}" />
    	<arg value="elastic.max_tuples_move=${elastic.max_tuples_move}" />
    	<arg value="elastic.min_gain_move=${elastic.min_gain_move}" />
    	<arg value="elastic.max_partitions_added=${elastic.max_partitions_added}" />
	<arg value="elastic.run_monitoring=${elastic.run_monitoring}" />
	<arg value="elastic.update_plan=${elastic.update_plan}" />
	<arg value="elastic.exec_reconf=${elastic.exec_reconf}" />
	<arg value="elastic.plan_in=${elastic.plan_in}" />
	<arg value="elastic.plan_out=${elastic.plan_out}" />
        <arg value="elastic.algo=${elastic.algo}" />
        <arg value="elastic.topk=${elastic.topk}" />
	    <arg value="elastic.root_table=${elastic.root_table}" />
	&common;
        <classpath refid='project.classpath' />
    <assertions><enable/></assertions>
    </java>
</target>

<!-- END PROJECT -->

</project>
