echo JAVA_HOME=$JAVA_HOME
java -version

cd $TRAVIS_BUILD_DIR/java
export STREAMS_INSTALL=/dev/null
ant compile.pure _jar

exit 0

# TBD - Work in progress
unset STREAMS_INSTALL

cd $TRAVIS_BUILD_DIR/test/python/topology
export PYTHONPATH=$TRAVIS_BUILD_DIR/com.ibm.streamsx.topology/opt/python/packages
python -u -m unittest test2.py