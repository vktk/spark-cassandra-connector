package com.datastax.driver.scala.core

import java.io.IOException
import java.net.InetAddress

import scala.collection.JavaConversions._
import com.datastax.driver.core.{Host, Cluster, Session}
import com.datastax.driver.scala.core.policies.LocalNodeFirstLoadBalancingPolicy
import com.datastax.driver.scala.core.conf._
import com.datastax.driver.scala.core.utils.Logging

/** Provides and manages connections to Cassandra.
  *
  * A `CassandraConnector` instance is serializable and can be safely sent over network,
  * because it automatically reestablishes the connection to the same cluster after
  * deserialization. Internally it saves a list of all nodes in the cluster, so a
  * connection can be established even if the host given in the initial config is down.
  *
  * Multiple `CassandraConnector`s in the same JVM connected to the same Cassandra cluster
  * will share a single underlying `Cluster` object. `CassandraConnector` will close the
  * underlying `Cluster` object automatically whenever it is not used i.e. no `Session` or
  * `Cluster` is open for longer than `cassandra.connection.keep_alive_ms` property value.
  *
  * A `CassandraConnector` object is configured from [[CassandraConnectorConf]] object which
  * can be either given explicitly or automatically configured from available system properties.
  * If a given setting is unavailable by explicit or system properties, the setting falls back
  * to a default value.
  *
  * @see [[CassandraSettings]] for system properties and their descriptions
  */
class CassandraConnector(conf: CassandraConnectorConf) extends Serializable with Logging {

  import CassandraConnector._

  protected[this] var _config = conf

  /** Known cluster hosts. This is going to return all cluster hosts after at least one successful connection has been made */
  def hosts = _config.hosts

  /** Configured native port */
  def nativePort = _config.nativePort

  /** Connection configurator */
  def configurator = _config.connectionFactory

  /** Returns a shared session to Cassandra and increases the internal open
    * reference counter. It does not release the session automatically,
    * so please remember to close it after use. Closing a shared session
    * decreases the session reference counter. If the reference count drops to zero,
    * the session may be physically closed. */
  def openSession() = {
    val session = sessionCache.acquire(_config)
    try {
      val allNodes = session.getCluster.getMetadata.getAllHosts.toSet
      val myNodes = LocalNodeFirstLoadBalancingPolicy.nodesInTheSameDC(_config.hosts, allNodes).map(_.getAddress)
      _config = _config.copy(hosts = myNodes)

      // We need a separate SessionProxy here to protect against double closing the session.
      // Closing SessionProxy is not really closing the session, because sessions are shared.
      // Instead, refcount is decreased. But double closing the same Session reference must not
      // decrease refcount twice. There is a guard in SessionProxy
      // so any subsequent close calls on the same SessionProxy are a no-ops.
      SessionProxy.wrapWithCloseAction(session)(sessionCache.release)
    }
    catch {
      case e: Throwable =>
        sessionCache.release(session)
        throw e
    }
  }

  /** Allows to use Cassandra `Session` in a safe way without
    * risk of forgetting to close it. The `Session` object obtained through this method
    * is a proxy to a shared, single `Session` associated with the cluster.
    * Internally, the shared underlying `Session` will be closed shortly after all the proxies
    * are closed. */
  def withSessionDo[T](code: Session => T): T = {
    closeResourceAfterUse(openSession()) { session =>
      code(SessionProxy.wrap(session))
    }
  }

  /** Allows to use Cassandra `Cluster` in a safe way without
    * risk of forgetting to close it. Multiple, concurrent calls might share the same
    * `Cluster`. The `Cluster` will be closed when not in use for some time.
    * It is not recommended to obtain sessions from this method. Use [[withSessionDo]]
    * instead which allows for proper session sharing. */
  def withClusterDo[T](code: Cluster => T): T = {
    withSessionDo { session =>
      code(session.getCluster)
    }
  }

  /** Returns the local node, if it is one of the cluster nodes. Otherwise returns any node. */
  def closestLiveHost: Host = withClusterDo { cluster =>
      LocalNodeFirstLoadBalancingPolicy
        .sortNodesByProximityAndStatus(_config.hosts, cluster.getMetadata.getAllHosts.toSet)
        .headOption
        .getOrElse(throw new IOException("Cannot connect to Cassandra: No hosts found"))
    }

  /** Automatically closes resource after use. Handy for closing streams, files, sessions etc.
    * Similar to try-with-resources in Java 7. */
  def closeResourceAfterUse[T, C <: { def close() }](closeable: C)(code: C => T): T =
    try code(closeable) finally {
      closeable.close()
    }

}

object CassandraConnector extends Logging {
  import Connection.KeepAliveMillisProperty

  /** From environment variable or java system property. */
  val keepAliveMillis = ((sys.env.find(_._1.endsWith(KeepAliveMillisProperty)) orElse
      sys.props.find(_._1.endsWith(KeepAliveMillisProperty))).map(_._2) getOrElse "250").toInt

  private val sessionCache = new RefCountedCache[CassandraConnectorConf, Session](
    createSession, destroySession, alternativeConnectionConfigs, releaseDelayMillis = keepAliveMillis)

  private def createSession(conf: CassandraConnectorConf)(implicit config: CassandraClusterConf): Session = {
    lazy val endpointsStr = conf.hosts.map(_.getHostAddress).mkString("{", ", ", "}") + ":" + conf.nativePort
    logDebug(s"Attempting to open native connection to Cassandra at $endpointsStr")
    val cluster = conf.connectionFactory.createCluster(conf)
    try {
      val clusterName = cluster.getMetadata.getClusterName
      logInfo(s"Connected to Cassandra cluster: $clusterName")
      cluster.connect()
    }
    catch {
      case e: Throwable =>
        cluster.close()
        throw new IOException(s"Failed to open native connection to Cassandra at $endpointsStr", e)
    }
  }

  private def destroySession(session: Session) {
    val cluster = session.getCluster
    val clusterName = cluster.getMetadata.getClusterName
    session.close()
    cluster.close()
    PreparedStatementCache.remove(cluster)
    logInfo(s"Disconnected from Cassandra cluster: $clusterName")
  }

  // This is to ensure the Cluster can be found by requesting for any of its hosts, or all hosts together.
  private def alternativeConnectionConfigs(conf: CassandraConnectorConf, session: Session): Set[CassandraConnectorConf] = {
    val cluster = session.getCluster
    val hosts = LocalNodeFirstLoadBalancingPolicy.nodesInTheSameDC(conf.hosts, cluster.getMetadata.getAllHosts.toSet)
    hosts.map(h => conf.copy(hosts = Set(h.getAddress))) + conf.copy(hosts = hosts.map(_.getAddress))
  }

  Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
    def run() {
      sessionCache.shutdown()
    }
  }))

  def apply(conf: CassandraConnectorConf): CassandraConnector =
    new CassandraConnector(conf)

  /** Returns a CassandraConnector created from defaults. */
  def apply(host: InetAddress, authConf: AuthConf = NoAuthConf): CassandraConnector =
    apply(CassandraConnectorConf(host, authConf))

  def apply(settings: CassandraSettings): CassandraConnector =
    apply(CassandraConnectorConf(settings))

  def evictCache(): Unit = {
    sessionCache.evict()
  }

}