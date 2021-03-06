function configure_hbase() {
  local OPTIND
  local OPTARG
  
  ROLES=$1
  shift
  
  # get parameters
  MASTER_HOST=
  ZOOKEEKER_QUORUM=
  PORT=
  CLOUD_PROVIDER=
  HBASE_TAR_URL=
  while getopts "m:q:p:c:u:" OPTION; do
    case $OPTION in
    m)
      MASTER_HOST="$OPTARG"
      ;;
    q)
      ZOOKEEPER_QUORUM="$OPTARG"
      ;;
    p)
      PORT="$OPTARG"
      ;;
    c)
      CLOUD_PROVIDER="$OPTARG"
      ;;
    u)
      HBASE_TAR_URL="$OPTARG"
      ;;
    esac
  done
  
  # determine machine name
  case $CLOUD_PROVIDER in
    ec2 | aws-ec2 )
      # Use public hostname for EC2
      SELF_HOST=`wget -q -O - http://169.254.169.254/latest/meta-data/public-hostname`
      ;;
    *)
      SELF_HOST=`/sbin/ifconfig eth0 | grep 'inet addr:' | cut -d: -f2 | awk '{ print $1}'`
      ;;
  esac
  
  # assign default URL if no other given (optional)
  HBASE_TAR_URL=${HBASE_TAR_URL:-http://archive.apache.org/dist/hbase/hbase-0.90.0/hbase-0.90.0.tar.gz}
  # derive details from the URL
  HBASE_TAR_FILE=${HBASE_TAR_URL##*/}
  # extract "version" or the name of the directory contained in the tarball,
  # but since hbase has used different namings use the directory instead.
  HBASE_VERSION=${HBASE_TAR_URL%/*.tar.gz}
  HBASE_VERSION=${HBASE_VERSION##*/}
  # simple check that we have a proper URL or default to use filename
  if [[ "${HBASE_VERSION:0:5}" != "hbase" ]]; then
    HBASE_VERSION=${HBASE_TAR_FILE%.tar.gz}
  fi
  HBASE_HOME=/usr/local/$HBASE_VERSION
  HBASE_CONF_DIR=$HBASE_HOME/conf

  case $CLOUD_PROVIDER in
  ec2 | aws-ec2 )
    MOUNT=/mnt
    ;;
  *)
    MOUNT=/data
    ;;
  esac

  mkdir -p $MOUNT/hbase
  chown hadoop:hadoop $MOUNT/hbase
  if [ ! -e $MOUNT/tmp ]; then
    mkdir $MOUNT/tmp
    chmod a+rwxt $MOUNT/tmp
  fi
  mkdir /etc/hbase
  ln -s $HBASE_CONF_DIR /etc/hbase/conf

  ##############################################################################
  # Modify this section to customize your HBase cluster.
  ##############################################################################
  cat > $HBASE_CONF_DIR/hbase-site.xml <<EOF
<?xml version="1.0"?>
<?xml-stylesheet type="text/xsl" href="configuration.xsl"?>
<configuration>
<property>
 <name>hbase.rootdir</name>
 <value>hdfs://$MASTER_HOST:8020/hbase</value>
</property>
<property>
 <name>hbase.cluster.distributed</name>
 <value>true</value>
</property>
<property>
 <name>hbase.zookeeper.quorum</name>
 <value>$ZOOKEEPER_QUORUM</value>
</property>
<property>
 <name>hbase.regionserver.handler.count</name>
 <value>100</value>
</property>
<property>
 <name>dfs.replication</name>
 <value>3</value>
</property>
<property>
 <name>zookeeper.session.timeout</name>
 <value>60000</value>
</property>
<property>
 <name>hbase.tmp.dir</name>
 <value>$MOUNT/tmp/hbase-\${user.name}</value>
</property>
</configuration>
EOF

  # override JVM options
  cat >> $HBASE_CONF_DIR/hbase-env.sh <<EOF
export HBASE_MASTER_OPTS="-Xms1000m -Xmx1000m -Xmn256m -XX:+UseConcMarkSweepGC -XX:+AggressiveOpts -verbose:gc -XX:+PrintGCDetails -XX:+PrintGCTimeStamps -Xloggc:/mnt/hbase/logs/hbase-master-gc.log"
export HBASE_REGIONSERVER_OPTS="-Xms2000m -Xmx2000m -Xmn256m -XX:+UseConcMarkSweepGC -XX:CMSInitiatingOccupancyFraction=88 -XX:+AggressiveOpts -verbose:gc -XX:+PrintGCDetails -XX:+PrintGCTimeStamps -Xloggc:/mnt/hbase/logs/hbase-regionserver-gc.log"
EOF

  # configure hbase for ganglia
  cat > $HBASE_CONF_DIR/hadoop-metrics.properties <<EOF
dfs.class=org.apache.hadoop.metrics.ganglia.GangliaContext
dfs.period=10
dfs.servers=$MASTER_HOST:8649
hbase.class=org.apache.hadoop.metrics.ganglia.GangliaContext
hbase.period=10
hbase.servers=$MASTER_HOST:8649
jvm.class=org.apache.hadoop.metrics.ganglia.GangliaContext
jvm.period=10
jvm.servers=$MASTER_HOST:8649
EOF

  # update classpath to include hbase jars and config
#  cat >> $HADOOP_HOME/conf/hadoop-env.sh <<EOF
#HADOOP_CLASSPATH="$HBASE_HOME/${HBASE_VERSION}.jar:$HBASE_HOME/lib/zookeeper-3.3.1.jar:$HBASE_CONF_DIR"
#EOF
  # configure Hadoop for Ganglia
#  cat > $HADOOP_HOME/conf/hadoop-metrics.properties <<EOF
#dfs.class=org.apache.hadoop.metrics.ganglia.GangliaContext
#dfs.period=10
#dfs.servers=$MASTER_HOST:8649
#jvm.class=org.apache.hadoop.metrics.ganglia.GangliaContext
#jvm.period=10
#jvm.servers=$MASTER_HOST:8649
#mapred.class=org.apache.hadoop.metrics.ganglia.GangliaContext
#mapred.period=10
#mapred.servers=$MASTER_HOST:8649
#EOF

  # keep PID files in a non-temporary directory
  sed -i -e "s|# export HBASE_PID_DIR=.*|export HBASE_PID_DIR=/var/run/hbase|" \
    $HBASE_CONF_DIR/hbase-env.sh
  mkdir -p /var/run/hbase
  chown -R hadoop:hadoop /var/run/hbase

  # set SSH options within the cluster
  sed -i -e 's|# export HBASE_SSH_OPTS=.*|export HBASE_SSH_OPTS="-o StrictHostKeyChecking=no"|' \
    $HBASE_CONF_DIR/hbase-env.sh

  # disable IPv6
  sed -i -e 's|# export HBASE_OPTS=.*|export HBASE_OPTS="-Djava.net.preferIPv4Stack=true"|' \
    $HBASE_CONF_DIR/hbase-env.sh

  # hbase logs should be on the /mnt partition
  sed -i -e 's|# export HBASE_LOG_DIR=.*|export HBASE_LOG_DIR=/var/log/hbase/logs|' \
    $HBASE_CONF_DIR/hbase-env.sh
  rm -rf /var/log/hbase
  mkdir $MOUNT/hbase/logs
  chown hadoop:hadoop $MOUNT/hbase/logs
  ln -s $MOUNT/hbase/logs /var/log/hbase
  chown -R hadoop:hadoop /var/log/hbase
  
  for role in $(echo "$ROLES" | tr "," "\n"); do
    case $role in
    hbase-master)
      start_hbase_daemon master
      ;;
    hbase-regionserver)
      start_hbase_daemon regionserver
      ;;
    hbase-restserver)
      start_hbase_daemon rest -p $PORT
      ;;
    hbase-avroserver)
      start_hbase_daemon avro -p $PORT
      ;;
    hbase-thriftserver)
      start_hbase_daemon thrift -p $PORT
      ;;
    esac
  done
}

function start_hbase_daemon() {
  if which dpkg &> /dev/null; then
    AS_HADOOP="su -s /bin/bash - hadoop -c"
  elif which rpm &> /dev/null; then
    AS_HADOOP="/sbin/runuser -s /bin/bash - hadoop -c"
  fi
  $AS_HADOOP "$HBASE_HOME/bin/hbase-daemon.sh start $1"
}

