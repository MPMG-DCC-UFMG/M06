package br.ufmg.cs.systems.fractal.mpmg

import br.ufmg.cs.systems.fractal._
import br.ufmg.cs.systems.fractal.subgraph._
import br.ufmg.cs.systems.fractal.util.collection.IntArrayList
import br.ufmg.cs.systems.fractal.util.{Logging, PairWritable}
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{IntWritable, Text}
import org.apache.spark.SparkConf
import org.apache.spark.sql.{DataFrame, SparkSession}
import com.hortonworks.spark.sql.hive.llap.HiveWarehouseBuilder;
import com.hortonworks.spark.sql.hive.llap.HiveWarehouseSession;
//import com.hortonworks.hwc.HiveWarehouseSession
import org.apache.spark.SparkContext
import org.apache.spark.graphx.{GraphLoader, PartitionStrategy}
import org.apache.spark.graphx.Graph
import org.apache.spark.graphx.VertexId

import java.io.{BufferedWriter, File, FileWriter}

import scala.collection.mutable.Map

import java.io.BufferedOutputStream
import org.graphframes.GraphFrame
import org.apache.spark.sql.DataFrame
import scala.collection.immutable.ListMap
/*
* Intern apps -- they can be used insider the apps that users can call.
*/

class UtilApp() extends Logging {
  def readConfig(configPath: String): ujson.Value = {
    logInfo(s"Reading config file from ${configPath}")
    ujson.read(scala.reflect.io.File(configPath).slurp)
  }

  def getCreateSparkSession(config: ujson.Value = null, configPath: String, configName: String): SparkSession = {
    logInfo("Creating Spark Session")
    val useConfig = if (config == null) readConfig(configPath) else config
    var conf = new SparkConf()
    useConfig(configName).arr.foreach(setting => {
      conf = conf.set(setting("name").str, setting("value").str)
    })

    SparkSession.builder.config(conf).enableHiveSupport().getOrCreate()
  }

  def getCreateHiveSession(ss: SparkSession): HiveWarehouseSession = {
    logInfo("Creating Hive Session (and respective spark session)")
    HiveWarehouseBuilder.session(ss).build()
  }
}

class HiveApp(val configPath: String) extends Logging {
  val utilApp = new UtilApp
  var databaseConfigs: ujson.Value = _
  var currentConfig: ujson.Value = _
  var hiveSession: HiveWarehouseSession = _

  def initConfigs: Unit = {
    currentConfig = utilApp.readConfig(configPath)
    databaseConfigs = currentConfig("database")
  }

  def initHiveConnector {
    val sparkSession = utilApp.getCreateSparkSession(currentConfig, null, "spark_database")
    hiveSession = HiveWarehouseBuilder.session(sparkSession).build()
  }

  initConfigs
  initHiveConnector

  /**
   * Read from hive/database write in disk
   */
  def readWriteInput(filePath: String): Unit = {
    if (filePath.isEmpty) return

    logInfo(s"\tLoading edges with query: ${databaseConfigs("input_query").str}")
    val edges = hiveSession.executeQuery(databaseConfigs("input_query").str)
    //logInfo(s"\tEdges loaded sample of 10: ${edges.take(10)}")
    //    todo: write using spark
    logInfo(s"\tWriting data to CSV at: ${filePath}")

    val buffer = new BufferedWriter(new FileWriter(new File(filePath)))
    edges.collect.foreach(edge => {
      buffer.write(s"${edge.get(0)} ${edge.get(1)}\n")
    })
    buffer.close()
    //edges.coalesce(1).write.format("com.databricks.spark.csv").mode("overwrite").save(filePath)
  }

  /**
   * Read from disk write in hive/database
   */
  def readWriteOutput(filePath: String): Unit = {
    if (filePath.isEmpty) return

    logInfo(s"\tReading data from: ${filePath}")
    val table = databaseConfigs("output_table_name").str
    val query = new StringBuilder(s"INSERT INTO TABLE ${table} VALUES")
    logInfo(s"\tExecuting query: ${query}")

    /*val linesIterator = Source.fromFile(filePath).getLines
    linesIterator.next
    for (line <- linesIterator) {
      query.append(s" (${line}),")
    }
    query.deleteCharAt(query.length - 1)

    logInfo(s"\tWriting data to table ${table} with query ${query}")
    hiveSession.executeUpdate(query.toString())*/
  }
}

class MapVerticesApp(val fractalGraph: FractalGraph,
                     algs: FractalAlgorithms) extends FractalSparkApp {
  var app: Map[IntWritable, Text] = _

  def execute: Unit = {
    val (mapf, elapsed) = FractalSparkRunner.time {
      algs.mapVertices(fractalGraph)
    }
    logInfo(s"MapVerticesApp" +
      s" graph=${fractalGraph} elapsed=${elapsed}"
    )

    app = mapf
  }
}

/*
* Apps that users can use directly via config.
*/

trait MPMGApp extends Logging {
  def execute: Unit

  def writeResults(filePath: String): Unit

  def writeResults(filePath: String, vertexMap: Map[IntWritable, Text]): Unit
}

class CliquesApp(
                  val fractalGraph: FractalGraph,
                  algs: FractalAlgorithms,
                  explorationSteps: Int) extends FractalSparkApp with MPMGApp {
  var app: Fractoid[VertexInducedSubgraph] = _

  def execute: Unit = {
    val cliquesRes = algs.cliques(fractalGraph, (explorationSteps + 1)).
      explore(explorationSteps)

    val (accums, elapsed) = FractalSparkRunner.time {
      cliquesRes.compute()
    }

    logInfo(s"CliquesOptApp" +
      s" explorationSteps=${explorationSteps}" +
      s" graph=${fractalGraph} " +
      s" numValidSubgraphs=${cliquesRes.numValidSubgraphs()} elapsed=${elapsed}"
    )

    app = cliquesRes
  }

  def writeResults(filePath: String, vertexMap: Map[IntWritable, Text]): Unit = {

    val conf = new org.apache.hadoop.conf.Configuration();
    val path = new Path(filePath)
    val fs = path.getFileSystem(conf)

    // Output file can be created from file system.
    val output = fs.create(path);

    // But BufferedOutputStream must be used to output an actual text file.
    val os = new BufferedOutputStream(output)
    os.write("Identificador da clique,Identificador do vértice participante\n".getBytes("UTF-8"))

    /*var i = 1
    app.mappedSubgraphs.collect().foreach(subgraph => {
      for (vertex: String <- subgraph.mappedWords) {
        os.write(s"${i},${vertex}\n".getBytes("UTF-8"))
      }
      i += 1 // todo: validate if is don't collide
    })*/

    var i = 1
    var iter_var = app.mappedSubgraphs.toLocalIterator
    while (iter_var.hasNext) {
     val subgraph = iter_var.next()
     for (vertex: String <- subgraph.mappedWords) {
        os.write(s"${i},${vertex}\n".getBytes("UTF-8"))
     }
      i += 1 
    }


    os.close()

    /*val buffer = new BufferedWriter(new FileWriter(new File(filePath)))
    buffer.write("Identificador da clique,Identificador do vértice participante\n")

    var i = 1
    app.mappedSubgraphs.collect.foreach(subgraph => {
      for (vertex: String <- subgraph.mappedWords) {
        buffer.write(s"${i},${vertex}\n")
      }
      i += 1 // todo: validate if is don't collide
    })

    buffer.close()*/

  }

  def writeResults(filePath: String): Unit = {
  }
}

class ShortestPathsApp(
                        val fractalGraph: FractalGraph,
                        algs: FractalAlgorithms,
                        explorationSteps: Int) extends FractalSparkApp with MPMGApp {
  var app: Fractoid[EdgeInducedSubgraph] = _

  def execute: Unit = {
    val (pathsf, elapsed) = FractalSparkRunner.time {
      algs.spaths(fractalGraph, explorationSteps)
    }
    logInfo(s"ShortestPathsApp" +
      s" explorationSteps=${explorationSteps}" +
      s" graph=${fractalGraph} " +
      s" numValidSubgraphs=${pathsf.numValidSubgraphs()} elapsed=${elapsed}"
    )

    app = pathsf
  }

  override def writeResults(filePath: String, vertexMap: Map[IntWritable, Text]): Unit = {
    val outputBuffer = new BufferedWriter(new FileWriter(new File(filePath)))
    outputBuffer.write("Identificador do caminho,Identificador do vértice participante,Vértice origem,Vértice destino\n")

    var i = 1
    app.aggregationMap[PairWritable[IntWritable, IntWritable], IntArrayList]("sps").foreach {
      case (pair, path) => {
        val it = path.iterator
        while (it.hasNext) {
          val id = new IntWritable(it.next())
          outputBuffer.write(s"${i},${vertexMap(id)},${pair.getLeft},${pair.getRight}\n")
        }
        i += 1 // todo: validate if is don't collide
      }
    }
    outputBuffer.close()
  }

  def writeResults(filePath: String): Unit = {
  }
}

//class ConnectedComponentsApp(var graph:Graph[Int, Int]) extends MPMGApp {
class ConnectedComponentsApp(var graph:GraphFrame) extends MPMGApp {
  //var app: Graph[VertexId, Int] = _
  var app:DataFrame = _

  def execute: Unit = {

    //val cc = graph.connectedComponents()
    //app = cc

    val cc = graph.connectedComponents.run()
    app = cc
  }

  def writeResults(filePath: String, vertexMap: Map[IntWritable, Text]): Unit = {
  }

  def writeResults(filePath: String): Unit = {

    /*val outputBuffer = new BufferedWriter(new FileWriter(new File(filePath)))
    outputBuffer.write("Identificador do vértice,Identificar do CC\n")
    app.vertices.collect().foreach(vertex => {
      outputBuffer.write(s"${vertex._1},${vertex._2}\n")
    })
    outputBuffer.close()*/
    app.write.csv(filePath)
  }

  def characterize(sc: SparkContext, filePath: String, delimiter: String): Unit = {

    val longs = sc.textFile(filePath)
    val counts = longs.map(x=>{x.split(delimiter)(1).toLong}).countByValue()
    val sorted = ListMap(counts.toSeq.sortWith(_._2 > _._2):_*)
    var mapToString = new PartialFunction[(Long,Long),String]{
      def apply(x:(Long,Long)) = x._1.toString + "," + x._2.toString;
      def isDefinedAt(x:(Long,Long)) = true;
    }

    var fw = new FileWriter(filePath.substring(7) + ".OUT")
    var bw = new BufferedWriter(fw)
    
    bw.write("Component,Tamanho\n");
    sorted.collect(mapToString).foreach(x => {
      bw.write(s"${x}\n")
    });
    
    bw.close();
    fw.close();
    
  }
}

class TriangleCountingGraphxApp(var graph:Graph[Int, Int]) extends MPMGApp {

  var app: Graph[Int, Int] = _

  def execute: Unit = {

    val tc = graph.triangleCount()
    app = tc
    
  }

  def writeResults(filePath: String, vertexMap: Map[IntWritable, Text]): Unit = {
  }

  def writeResults(filePath: String): Unit = {

    val file = new File(filePath)
    val outputBuffer = new BufferedWriter(new FileWriter(file))
    outputBuffer.write("id-vertice,qtd-triangulo\n")
    app.vertices.collect().foreach(vertex => {
      outputBuffer.write(s"${vertex._1},${vertex._2}\n")
    })
    outputBuffer.close()
  }
}

class TriangleCountingApp(var graph:GraphFrame) extends MPMGApp {

  var app:DataFrame = _

  def execute: Unit = {

    //val tc = graph.triangleCount()
    //app = tc
    val results = graph.triangleCount.run()
    app = results.select("id", "count").orderBy(org.apache.spark.sql.functions.col("count").desc)
  }

  def writeResults(filePath: String, vertexMap: Map[IntWritable, Text]): Unit = {
  }

  def writeResults(filePath: String): Unit = {

    /*val outputBuffer = new BufferedWriter(new FileWriter(new File(filePath)))
    outputBuffer.write("Identificador do vértice,Qtd. de triângulos que participa\n")
    app.vertices.collect().foreach(vertex => {
      outputBuffer.write(s"${vertex._1},${vertex._2}\n")
    })
    outputBuffer.close()*/
    app.write.csv(filePath)
  }

  def characterize(sc: SparkContext, filePath: String, delimiter: String): Unit = {

    val longs = sc.textFile(filePath)
    val counts = longs.map(x=>{x.split(delimiter)})
    val sorted = counts.sortBy(_.apply(1).toInt, false)
    var mapToString = new PartialFunction[Array[String],String]{
      def apply(x:Array[String]) = x(0) + "," + x(1);
      def isDefinedAt(x:Array[String]) = true;
    }


    var fw = new FileWriter(filePath.substring(7) + ".OUT")
    var bw = new BufferedWriter(fw)

    bw.write("Vertice,Triangulos\n");
    sorted.collect(mapToString).foreach(x => {
      bw.write(s"${x}\n")
    });

    bw.close();
    fw.close();

  }



}

class ReadDatabaseApp(val hs: HiveWarehouseSession, val query: String
                     ) extends MPMGApp {
  var app: DataFrame = _

  def execute: Unit = {
    logInfo(s"\tLoading edges with query: " + query)
    app = hs.executeQuery(query)
  }

  override def writeResults(filePath: String, vertexMap: Map[IntWritable, Text]): Unit = {
  }

  def writeResults(filePath: String): Unit = {
    if (filePath.isEmpty) return

    logInfo(s"\tWriting data to CSV at: ${filePath}")
    val conf = new org.apache.hadoop.conf.Configuration();
    val path = new Path(filePath)
    val fs = path.getFileSystem(conf)

    // Output file can be created from file system.
    /*val output = fs.create(path);
    // But BufferedOutputStream must be used to output an actual text file.
    val os = new BufferedOutputStream(output)
    app.collect.foreach(edge => {
      os.write(s"${edge.get(0)} ${edge.get(1)}\n".getBytes("UTF-8"))
    })
    os.close()*/

    /*val buffer = new BufferedWriter(new FileWriter(new File(filePath)))
    app.collect.foreach(edge => {
      buffer.write(s"${edge.get(0)} ${edge.get(1)}\n")
    })
    buffer.close()*/

    app.write.format("com.databricks.spark.csv").mode("overwrite").save(filePath)

    //app.coalesce(1).write.format("com.databricks.spark.csv").option("delimiter", " ").mode("overwrite").(filePath)
  }
}

object MPMGSparkRunner {
  def time[R](block: => R): (R, Long) = {
    val t0 = System.currentTimeMillis()
    val result = block // call-by-name
    val t1 = System.currentTimeMillis()
    (result, t1 - t0)
  }

  def main(args: Array[String]) {
    //args
    val configPath = args(0)
    val utilApp = new UtilApp
    val config = utilApp.readConfig(configPath)

    //create input file for fractal 
    val appConfig = config("app")

    //running fractal application
    val algs = new FractalAlgorithms //TODO: extends this class to be Algorithms (Fractal and Database algorithms)

    val outputPath = appConfig("output_path").str
    if (outputPath.isEmpty) throw new RuntimeException(s"Unknown output_path")

    appConfig("name").str.toLowerCase match {
      case "cliques" => {
        /* fractal and its spark initialization */
        val ss = utilApp.getCreateSparkSession(config, null, "spark_fractal")
        if (!ss.sparkContext.isLocal) Thread.sleep(10000) // TODO: this is ugly but have to make sure all spark executors are up by the time we start executing fractal applications
        val fc = new FractalContext(ss.sparkContext)
        val inputPath = appConfig("input_path").str
        if (inputPath.isEmpty) throw new RuntimeException(s"Unknown input_fractal_path")
        val fractalGraph = fc.textFile(inputPath, "br.ufmg.cs.systems.fractal.graph.EdgeListGraph")

        /* Execute app */
        val vertexMap = new MapVerticesApp(fractalGraph, algs) //TODO: put this as an application (map the input and the output of fractal)
        //vertexMap.execute
        val app = new CliquesApp(fractalGraph, algs, appConfig("steps").num.toInt)
        app.execute

        //write output results
        app.writeResults(outputPath, vertexMap.app)
        fc.stop()
        ss.stop()

      }
      case "spaths" => {
        /* fractal and its spark initialization */
        val ss = utilApp.getCreateSparkSession(config, null, "spark_fractal")
        if (!ss.sparkContext.isLocal) Thread.sleep(10000) // TODO: this is ugly but have to make sure all spark executors are up by the time we start executing fractal applications
        val fc = new FractalContext(ss.sparkContext)
        val inputPath = appConfig("input_path").str
        if (inputPath.isEmpty) throw new RuntimeException(s"Unknown input_path")
        val fractalGraph = fc.textFile(inputPath, "br.ufmg.cs.systems.fractal.graph.EdgeListGraph")

        /* Execute app */
        val vertexMap = new MapVerticesApp(fractalGraph, algs) //TODO: put this as an application (map the input and the output of fractal)
        vertexMap.execute //TODO: remove this from here, use as an application
        val app = new ShortestPathsApp(fractalGraph, algs, appConfig("steps").num.toInt)
        app.execute

        //write output results
        app.writeResults(outputPath, vertexMap.app)
        fc.stop()
        ss.stop()
      }
      case "read_database" => {
        /*  database/hive and its spark initialization	 */
        val ss = utilApp.getCreateSparkSession(config, null, "spark_database")
        val hs = utilApp.getCreateHiveSession(ss)

        /* Execute app */
        val app = new ReadDatabaseApp(hs, appConfig("query").str)
        app.execute
        app.writeResults(outputPath)
        ss.close()
      }
      case "connected_components" => {

        val inputPath = appConfig("input_path").str
        if (inputPath.isEmpty) throw new RuntimeException(s"Unknown input_path")

        var delimiter = appConfig("delimiter").str
        if (delimiter.isEmpty) delimiter = ","

        var checkpointDir = appConfig("checkpoint_dir").str
        if (checkpointDir.isEmpty) checkpointDir = "/checkpoint-dir"

        /* fractal and its spark initialization */
        val ss = utilApp.getCreateSparkSession(config, null, "spark_fractal")
        if (!ss.sparkContext.isLocal) Thread.sleep(10000) // TODO: this is ugly but have to make sure all spark executors are up by the time we start executing fractal applications

        val sc = ss.sparkContext
        
        //import ss.implicits._ 
        //val graph = GraphLoader.edgeListFile(sc, inputPath)
        /*val graph = Graph.fromEdgeTuples(
            ss.read
                // Adjust separator if needed
                .options(Map("delimiter" -> ","))
                .csv(inputPath)
                .as[(Long, Long)]
                .rdd,
            defaultValue = 0
        )*/
        sc.setCheckpointDir(checkpointDir)
        var df = ss.read.option("inferSchema","true").option("delimiter", delimiter).csv(inputPath).toDF("src", "dst")
	df = df.repartition(300) 

        val graph = GraphFrame.fromEdges(df)
        val app = new ConnectedComponentsApp(graph)
        app.execute

        //write output results
        app.writeResults(outputPath)

        app.characterize(sc, outputPath, delimiter)
        
        sc.stop()
        ss.stop()

      }
      case "tricount" => {

        val inputPath = appConfig("input_path").str
        if (inputPath.isEmpty) throw new RuntimeException(s"Unknown input_path")

        var delimiter = appConfig("delimiter").str
        if (delimiter.isEmpty) delimiter = ","

        var checkpointDir = appConfig("checkpoint_dir").str
        if (checkpointDir.isEmpty) checkpointDir = "/checkpoint-dir"

        /* fractal and its spark initialization */
        val ss = utilApp.getCreateSparkSession(config, null, "spark_fractal")
        if (!ss.sparkContext.isLocal) Thread.sleep(10000) // TODO: this is ugly but have to make sure all spark executors are up by the time we start executing fractal applications

        val sc = ss.sparkContext

        // Load the edges in canonical order and partition the graph for triangle count
        //val graph = GraphLoader.edgeListFile(sc, inputPath, true).partitionBy(PartitionStrategy.RandomVertexCut)
        //val app = new TriangleCountingGraphxApp(graph)
        //app.execute

        sc.setCheckpointDir(checkpointDir)
        var df = ss.read.option("inferSchema","true").option("delimiter", delimiter).csv(inputPath).toDF("src", "dst")
        df = df.repartition(300)
        val graph = GraphFrame.fromEdges(df)
        val app = new TriangleCountingApp(graph)
        app.execute 

        //write output results
        app.writeResults(outputPath)

        app.characterize(sc, outputPath, delimiter)
        sc.stop()
        ss.stop()
      }
      case appName => {
        throw new RuntimeException(s"Unknown app: ${appName}")
      }
    }
  }
}

