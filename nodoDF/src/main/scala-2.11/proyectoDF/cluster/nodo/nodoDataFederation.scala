package proyectoDF.cluster.nodo


import java.nio.charset.Charset
import java.util

import language.postfixOps
import akka.actor.{Actor, ActorPath, ActorRef, ActorSystem, FSM, Terminated}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Publish, Send, SendToAll, Subscribe}
import com.typesafe.config.ConfigFactory
import org.apache.zookeeper.{CreateMode, ZooDefs}
import proyectoDF.cluster.mensajeria.{inicializadoDF, metaDataDF, peticionDF, respuestaDF}
import org.slf4j.{Logger, LoggerFactory}
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.framework.recipes.cache.NodeCache
import org.apache.curator.framework.recipes.cache.NodeCacheListener
import org.apache.spark.sql.{DataFrame, Row}

import scala.util.matching.Regex.Match
import scala.util.parsing.json.JSONObject
import scala.collection.JavaConversions._


// FSM: Estados
sealed trait EstadoNodoDF
case object Inicializando extends EstadoNodoDF
case object Actualizando extends EstadoNodoDF
case object Activo extends EstadoNodoDF
case object SinSesion extends EstadoNodoDF

// FSM: Datos
sealed trait Datos
case object SinDatos extends Datos

class nodoDataFederation extends Actor with FSM[EstadoNodoDF, Datos]  {


  var comandoSQL : String = ""
  var tablaSQL : String = ""

  def parsearSQL(sentencia: String) = {
    // Parseamos la peticion
    val patronComando = "( )*([A-Z]+)( )+(.)*".r
    val patronCreate =  "( )*(CREATE|DROP)( )*(TABLE)( )+([A-Z]+)(.)*".r
    val patronSelect = "( )*(SELECT)( )+(.)+( )+(FROM)( )+([A-Z]+)( )*(.)*".r

    val patronComando (b1, comando: String, b2, b3) = sentencia.toUpperCase
    comandoSQL = comando
    if (comando == "CREATE" || comando == "DROP") {
       val patronCreate(b1, create, b2, t1, b3, tabla, b4) = sentencia.toUpperCase
       println("TABLA = " + tabla)
      tablaSQL = tabla
    }
    else {
      if (comando == "SELECT") {
        val patronSelect(d1, d2, d3, d4, d5, d6, d7, tabla, d9, d10) = sentencia.toUpperCase
        println("TABLA = " + tabla)
        tablaSQL = tabla
      }
      else {
        comandoSQL = "ERROR"
      }
    }
  }


  // Variables para replicacion metadata
  val mediator = DistributedPubSub(context.system).mediator

  startWith(Inicializando, SinDatos)

  val config = ConfigFactory.load()
  val masterSpark = config.getString("sparkConfig.master-url")
  val zKHosts = config.getString("zkConfig.hosts")

  // Conexion a Spark
  var spark = org.apache.spark.sql.SparkSession.builder
    .master(masterSpark)
    .appName("SparkNodoConexion")
    .config("spark.cores.max", "2")
    .getOrCreate()

  // Conexion a Zookeeper utilizando libreria Cliente
  private val logger = LoggerFactory.getLogger(this.getClass.getName)

  val retryPolicy = new ExponentialBackoffRetry(1000, 3)
  val curatorZookeeperClient = CuratorFrameworkFactory.newClient(zKHosts, retryPolicy)
  curatorZookeeperClient.start
  curatorZookeeperClient.getZookeeperClient.blockUntilConnectedOrTimedOut

  val zkPathMetadata = "/METADATA"
  if (curatorZookeeperClient.checkExists().forPath(zkPathMetadata) == null)
    curatorZookeeperClient.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(zkPathMetadata)

  // Recuperar metadata existente y aplicarlo
  // Anteriormente hemos chequeado la existencia del nodo metadata y lo hemos creado de no ser asi
  // 1. Leemos la lista de nodos para metadata de tablas
  var listaTablasZK : java.util.List[String] = curatorZookeeperClient.getChildren().forPath(zkPathMetadata)
  listaTablasZK.foreach( tabla => {
      println (tabla)
      var listaComandosZK : util.List[String] = curatorZookeeperClient.getChildren().forPath(zkPathMetadata+"/"+tabla)
    listaComandosZK.foreach(comandoZK => {
        val bytesComandoZK = curatorZookeeperClient.getData().forPath(zkPathMetadata+"/"+tabla+"/"+comandoZK)
        val textoComandoZK = new String (bytesComandoZK, Charset.forName("UTF-8"))
        println(comandoZK+": "+textoComandoZK)
        var est :String = ""
        var msj :String = ""
        procesarPeticion(textoComandoZK, est, msj)
    })
    }
  )


  // Cuando ya hemos terminado la inicializacion del nodo lo ponemos en estado Activo para empezar a aceptar peticiones
  self ! inicializadoDF()

  def procesarPeticion (textoPeticion: String, estado: String, mensaje: String) : String = {

    var salida: String = null
    def convertRowToJSON(row: Row): String = {
      val m = row.getValuesMap(row.schema.fieldNames)
      JSONObject(m).toString()
    }

    println(s"Procesamos comando SQL DataFederation (): '$textoPeticion")

    // Ejecutar comando
    val resultado = spark.sql(textoPeticion)

    if (resultado.rdd.isEmpty() == false) {
       salida = convertRowToJSON(resultado.collect()(0))
    }
    else {
      salida = null
    }
    salida
  }

  when (Activo) {
    case Event(peticionDF(texto, est, msj), SinDatos) =>

      // Recibimos mensaje de peticion
      println(s"Recibida peticion en DataFederation (): '$texto'")

      // Parseamos la peticion
      parsearSQL(texto)

      println ("COMANDO = " + comandoSQL)
      println("TABLA = " + tablaSQL)

      // procesamos la peticion
      sender() ! respuestaDF(procesarPeticion(texto, est, msj))

      // Enviamos el mensaje como metadata al resto de nodos salvo él mismo
      if (comandoSQL == "CREATE" || comandoSQL == "DROP") {
        println("Mandamos Metadata")
        mediator ! SendToAll("/user/nodo", metaDataDF(texto), allButSelf = true)

        // Almacenamos Metadataen ZooKeeper
        val zkPathTabla = zkPathMetadata+"/"+tablaSQL
        val zkPathComando = zkPathMetadata+"/"+tablaSQL+"/"+comandoSQL

        if (curatorZookeeperClient.checkExists().forPath(zkPathTabla) == null)
          curatorZookeeperClient.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(zkPathTabla)

        if (curatorZookeeperClient.checkExists().forPath(zkPathComando) == null)
          curatorZookeeperClient.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(zkPathComando)

        curatorZookeeperClient.setData().forPath(zkPathComando, texto.getBytes())

      }

      stay() using SinDatos


    case Event(metaDataDF(texto), SinDatos) =>
      // Si recibimos metadatos enviamos la peticion pero ya no se reenvia a su vez como metadata
      println(s"Recibido Metadata: '$texto'")
      var est :String = ""
      var msj :String = ""
      procesarPeticion(texto, est, msj)

      stay() using SinDatos
  }

  when (Inicializando) {
    case Event(peticionDF(texto, estado, mensaje), SinDatos) =>
      // Recibida Peticion cuando aun no hay conexion a Spark
      println(s"Recibida peticion cuando aun no hay conexion a Spark")

      var salida : String = null
      salida = "El servicio del DataFederation aun no está activo. Espere unos segundos"
      sender () ! respuestaDF(salida)

      stay() using SinDatos

    case Event(inicializadoDF(), SinDatos) =>
      // Recibida Peticion cuando aun no hay conexion a Spark
      println(s"Recibida Mensaje de Fin de Inicializacion")

      goto (Activo) using SinDatos

  }


}