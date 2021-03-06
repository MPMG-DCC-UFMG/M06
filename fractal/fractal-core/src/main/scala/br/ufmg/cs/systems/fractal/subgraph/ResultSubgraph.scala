package br.ufmg.cs.systems.fractal.subgraph

import br.ufmg.cs.systems.fractal.conf.{Configuration, SparkConfiguration}
import br.ufmg.cs.systems.fractal.graph.BasicMainGraph
import org.apache.hadoop.io.Writable

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 *
 */
trait ResultSubgraph[T] extends Writable {
  def words: Array[T]
  def combinations(k: Int): Iterator[ResultSubgraph[T]]
  def toInternalSubgraph[E <: Subgraph](config: SparkConfiguration[E]): E
  def toMappedSubgraph(config: SparkConfiguration[_]): ResultSubgraph[_]
  var mappedWords: ArrayBuffer[String]

  override def hashCode(): Int = {
    words.toSet.hashCode()
  }

  override def equals(_other: Any): Boolean = {
    if (_other == null || getClass != _other.getClass) return false
    return equals (_other.asInstanceOf[ResultSubgraph[T]])
  }

  def equals(other: ResultSubgraph[T]): Boolean = {
    if (other == null) return false

    if (this.words.length != other.words.length) return false
    return this.words.toSet == other.words.toSet
  }

  def vertices = {
    if (mappedWords != null) mappedWords.toArray else words
  }

}

object ResultSubgraph {

  def apply (strSubgraph: String) = {
    if (strSubgraph contains "-")
      ESubgraph (strSubgraph)
    else
      VSubgraph (strSubgraph)
  }

  def apply(subgraph: Subgraph, config: Configuration[_]) = {
    if (subgraph.isInstanceOf[EdgeInducedSubgraph]) {
      val mainGraph = config.getMainGraph[BasicMainGraph[_,_]]
      val edges = new Array [(Int,Int)] (subgraph.getNumEdges)
      val edgesIter = subgraph.getEdges.iterator
      var i = 0
      while (edgesIter.hasNext) {
        val e = mainGraph.getEdge(edgesIter.next)
        edges(i) = (e.getSourceId, e.getDestinationId)
        i += 1
      }
      new ESubgraph (edges)
    } else if (subgraph.isInstanceOf[VertexInducedSubgraph]) {
      new VSubgraph (subgraph.getVertices.toIntArray)
    } else {
      throw new RuntimeException(s"Unknown subgraph type: ${subgraph}")
    }
  }
}
