package nodes.learning

import breeze.linalg.{argmax, DenseMatrix, DenseVector, Vector}
import org.apache.spark.mllib.classification.NaiveBayes
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD
import pipelines.LabelEstimator
import pipelines.Transformer
import utils.MLlibUtils.breezeVectorToMLlib

/**
 * A Multinomial Naive Bayes model that transforms feature vectors to vectors containing
 * the log posterior probabilities of the different classes
 *
 * @param labels list of class labels, ranging from 0 to (C - 1) inclusive
 * @param pi log of class priors, whose dimension is C, number of labels
 * @param theta log of class conditional probabilities, whose dimension is C-by-D,
 *              where D is number of features
 */
class NaiveBayesModel(
    val labels: Array[Int],
    val pi: Array[Double],
    val theta: Array[Array[Double]]) extends Transformer[Vector[Double], DenseVector[Double]] {

  private val brzPi = new DenseVector[Double](pi.length)
  private val brzTheta = new DenseMatrix[Double](theta.length, theta(0).length)

  {
    // Need to put an extra pair of braces to prevent Scala treating `i` as a member.
    var i = 0
    while (i < theta.length) {
      brzPi(labels(i)) = pi(i)
      var j = 0
      while (j < theta(i).length) {
        brzTheta(labels(i), j) = theta(i)(j)
        j += 1
      }
      i += 1
    }
  }

  /**
   * Transforms feature vectors to vectors containing the log(posterior probabilities) of the different classes
   * according to this naive bayes model.
   *
   * @param in The input RDD of features
   * @return Log-posterior probabilites of the classes for the input features
   */
  override def apply(in: RDD[Vector[Double]]): RDD[DenseVector[Double]] = {
    val brzPiBroadcast = in.context.broadcast(brzPi)
    val brzThetaBroadcast = in.context.broadcast(brzTheta)

    in.mapPartitions { iter =>
      val pi = brzPiBroadcast.value
      val theta = brzThetaBroadcast.value
      iter.map(x => pi + theta * x)
    }
  }
}

/**
 * A LabelEstimator which learns a multinomial naive bayes model from training data.
 * Outputs a Transformer that maps features to vectors containing the log-posterior-probabilities
 * of the various classes according to the learned model.
 *
 * @param lambda The lambda parameter to use for the naive bayes model
 */
case class NaiveBayesEstimator(numClasses: Int, lambda: Double = 1.0)  extends LabelEstimator[RDD[Vector[Double]], RDD[DenseVector[Double]], RDD[Int]] {
  override def fit(in: RDD[Vector[Double]], labels: RDD[Int]): NaiveBayesModel = {
    val labeledPoints = labels.zip(in).map(x => LabeledPoint(x._1, breezeVectorToMLlib(x._2)))
    val model = NaiveBayes.train(labeledPoints, lambda)

    new NaiveBayesModel(model.labels.map(_.toInt), model.pi, model.theta)
  }
}