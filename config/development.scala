import scala.collection.JavaConversions._
import com.twitter.flockdb.config._
import com.twitter.gizzard.config._
import com.twitter.querulous.config._
import com.twitter.querulous.StatsCollector
import com.twitter.conversions.time._
import com.twitter.conversions.storage._
import com.twitter.flockdb.shards.QueryClass
import com.twitter.flockdb.Priority
import com.twitter.ostrich.admin.config.AdminServiceConfig
import com.twitter.logging.Level
import com.twitter.logging.config._

trait Credentials extends Connection {
  val env = System.getenv().toMap
  val username = env.get("DB_USERNAME").getOrElse("root")
  val password = env.get("DB_PASSWORD").getOrElse("")
}

class ProductionQueryEvaluator extends QueryEvaluator {
  autoDisable = new AutoDisablingQueryEvaluator {
    val errorCount = 100
    val interval = 60.seconds
  }

  database.memoize = true
  database.pool = new ApachePoolingDatabase {
    sizeMin = 40
    sizeMax = 40
    maxWait = 100.millis
    minEvictableIdle = 60.seconds
    testIdle = 1.second
    testOnBorrow = false
  }

  database.timeout = new TimingOutDatabase {
    open = 50.millis
    poolSize = 10
    queueSize = 10000
  }
}

class ProductionNameServerReplica(host: String) extends Mysql {
  val connection = new Connection with Credentials {
    val hostnames = Seq(host)
    val database = "flockdb_development"
  }

  queryEvaluator = new ProductionQueryEvaluator {
    database.pool.foreach { p =>
      p.sizeMin = 1
      p.sizeMax = 1
      p.maxWait = 1.second
    }

    database.timeout.foreach { t =>
      t.open = 1.second
    }

    query.timeouts = Map(
      QueryClass.Select -> QueryTimeout(1.second),
      QueryClass.Execute -> QueryTimeout(1.second),
      QueryClass.SelectCopy -> QueryTimeout(15.seconds),
      QueryClass.SelectModify -> QueryTimeout(3.seconds),
      QueryClass.SelectSmall                -> QueryTimeout(1.second),
      QueryClass.SelectIntersection         -> QueryTimeout(1.second),
      QueryClass.SelectMetadata             -> QueryTimeout(1.second),
      QueryClass.SelectMetadataIntersection -> QueryTimeout(1.second)
    )
  }
}

new FlockDB {
  aggregateJobsPageSize = 500

  val server = new FlockDBServer with TSelectorServer {
    timeout = 100.millis
    idleTimeout = 60.seconds
    threadPool.minThreads = 250
    threadPool.maxThreads = 250
  }

  val nameServer = new com.twitter.gizzard.config.NameServer {
    mappingFunction = ByteSwapper
    jobRelay = NoJobRelay

    val replicas = Seq(
      new ProductionNameServerReplica("localhost")
    )
  }

  jobInjector.timeout = 100.millis
  jobInjector.idleTimeout = 60.seconds
  jobInjector.threadPool.minThreads = 30

  val replicationFuture = new Future {
    poolSize = 100
    maxPoolSize = 100
    keepAlive = 5.seconds
    timeout = 6.seconds
  }

  val readFuture = new Future {
    poolSize = 100
    maxPoolSize = 100
    keepAlive = 5.seconds
    timeout = 6.seconds
  }

  val databaseConnection = new Credentials {
    val hostnames = Seq("localhost")
    val database = "edges_test"
    urlOptions = Map("rewriteBatchedStatements" -> "true")
  }

  val edgesQueryEvaluator = new ProductionQueryEvaluator

  val materializingQueryEvaluator = new ProductionQueryEvaluator {
    database.pool.foreach { p =>
      p.sizeMin = 1
      p.sizeMax = 1
      p.maxWait = 1.second
    }
  }

  class DevelopmentScheduler(val name: String) extends Scheduler {
    override val jobQueueName = name + "_jobs"
    val schedulerType = new KestrelScheduler {
      val queuePath = "."
    }

    errorLimit = 100
    errorRetryDelay = 15.minutes
    errorStrobeInterval = 1.second
    perFlushItemLimit = 100
    jitterRate = 0
  }

  val jobQueues = Map(
    Priority.High.id    -> new DevelopmentScheduler("edges") { threads = 32 },
    Priority.Medium.id  -> new DevelopmentScheduler("copy") { threads = 12; errorRetryDelay = 60.seconds },
    Priority.Low.id     -> new DevelopmentScheduler("edges_slow") { threads = 2 }
  )

  val adminConfig = new AdminServiceConfig {
    httpPort = Some(9990)
  }

  loggers = List(new LoggerConfig {
    level = Some(Level.INFO)
    handlers = List(
      new ThrottledHandlerConfig {
        duration = 60.seconds
        maxToDisplay = 10
        handler = new FileHandlerConfig {
          filename = "development.log"
          roll = Policy.Hourly
        }
      }
    )
  })
}
