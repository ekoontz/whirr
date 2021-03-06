function install_zookeeper() {

  ZK_VERSION=${1:-3.3.0}
  ZOOKEEPER_HOME=/usr/local/zookeeper-$ZK_VERSION
  ZK_CONF_DIR=/etc/zookeeper/conf
  ZK_LOG_DIR=/var/log/zookeeper
  ZK_DATA_DIR=$ZK_LOG_DIR/txlog
  
  zk_tar_url=http://www.apache.org/dist/hadoop/zookeeper/zookeeper-$ZK_VERSION/zookeeper-$ZK_VERSION.tar.gz
  zk_tar_file=`basename $zk_tar_url`
  zk_tar_md5_file=`basename $zk_tar_url.md5`
  
  curl="curl --retry 3 --silent --show-error --fail"
  for i in `seq 1 3`;
  do
    $curl -O $zk_tar_url
    $curl -O $zk_tar_url.md5
    if md5sum -c $zk_tar_md5_file; then
      break;
    else
      rm -f $zk_tar_file $zk_tar_md5_file
    fi
  done
  
  if [ ! -e $zk_tar_file ]; then
    echo "Failed to download $zk_tar_url. Aborting."
    exit 1
  fi
  
  tar zxf $zk_tar_file -C /usr/local
  rm -f $zk_tar_file $zk_tar_md5_file
  
  echo "export ZOOKEEPER_HOME=$ZOOKEEPER_HOME" >> /etc/profile
  echo 'export PATH=$ZOOKEEPER_HOME/bin:$PATH' >> /etc/profile
  
  mkdir -p /mnt/zookeeper/logs
  ln -s /mnt/zookeeper/logs $ZK_LOG_DIR
  mkdir -p $ZK_LOG_DIR/txlog
  mkdir -p $ZK_CONF_DIR
  cp $ZOOKEEPER_HOME/conf/log4j.properties $ZK_CONF_DIR
  
  sed -i -e "s|log4j.rootLogger=INFO, CONSOLE|log4j.rootLogger=INFO, ROLLINGFILE|" \
         -e "s|log4j.appender.ROLLINGFILE.File=zookeeper.log|log4j.appender.ROLLINGFILE.File=$ZK_LOG_DIR/zookeeper.log|" \
      $ZK_CONF_DIR/log4j.properties
  
  # Install a CRON task for data directory cleanup
  ZK_JAR=$ZOOKEEPER_HOME/zookeeper-$ZK_VERSION.jar
  ZK_LOG4J_JAR=`echo $ZOOKEEPER_HOME/lib/log4j-*.jar`
  
  CRON="0 0 * * * java -cp $ZK_JAR:$ZK_LOG4J_JAR:$ZK_CONF_DIR org.apache.zookeeper.server.PurgeTxnLog $ZK_DATA_DIR $ZK_DATA_DIR -n 10"
  crontab -l 2>/dev/null | { cat; echo "$CRON"; } | sort | uniq | crontab -
      
  # Ensure ZooKeeper starts on boot
  sed -i -e "s/exit 0//" /etc/rc.local
cat >> /etc/rc.local <<EOF
ZOOCFGDIR=$ZK_CONF_DIR $ZOOKEEPER_HOME/bin/zkServer.sh start > /dev/null 2>&1 &
EOF

}
