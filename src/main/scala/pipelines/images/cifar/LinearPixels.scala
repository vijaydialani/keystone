package pipelines.images.cifar

import breeze.linalg.DenseVector
import evaluation.MulticlassClassifierEvaluator
import loaders.CifarLoader
import nodes.images.{GrayScaler, ImageExtractor, ImageVectorizer, LabelExtractor}
import nodes.learning.LinearMapEstimator
import nodes.util.{Cacher, ClassLabelIndicatorsFromIntLabels, MaxClassifier}
import org.apache.spark.{SparkConf, SparkContext}
import pipelines.Logging
import scopt.OptionParser


object LinearPixels extends Logging {
  val appName = "LinearPixels"
  case class LinearPixelsConfig(trainLocation: String = "", testLocation: String = "")

  def run(sc: SparkContext, config: LinearPixelsConfig) = {
    val numClasses = 10

    // Load and cache the training data.
    val trainData = CifarLoader(sc, config.trainLocation).cache()

    // A featurizer maps input images into vectors. For this pipeline, we'll also convert the image to grayscale.
    val featurizer = GrayScaler then ImageVectorizer
    val labelExtractor = LabelExtractor then ClassLabelIndicatorsFromIntLabels(numClasses) then new Cacher[DenseVector[Double]]

    // Our training features are the featurizer applied to our training data.
    val trainImages = ImageExtractor(trainData)
    val trainFeatures = featurizer(trainImages)
    val trainLabels = labelExtractor(trainData)

    // We estimate our model as by calling a linear solver on our data.
    val model = LinearMapEstimator().fit(trainFeatures, trainLabels)

    // The final prediction pipeline is the composition of our featurizer and our model.
    // Since we end up using the results of the prediction twice, we'll add a caching node.
    val predictionPipeline = featurizer then model then MaxClassifier

    // Calculate training error.
    val trainEval = MulticlassClassifierEvaluator(predictionPipeline(trainImages), LabelExtractor(trainData), numClasses)

    // Do testing.
    val testData = CifarLoader(sc, config.testLocation)
    val testImages = ImageExtractor(testData)
    val testLabels = labelExtractor(testData)

    val testEval = MulticlassClassifierEvaluator(predictionPipeline(testImages), LabelExtractor(testData), numClasses)

    logInfo(s"Training accuracy: \n${trainEval.totalAccuracy}")
    logInfo(s"Test accuracy: \n${testEval.totalAccuracy}")

    predictionPipeline
  }

  def parse(args: Array[String]): LinearPixelsConfig = new OptionParser[LinearPixelsConfig](appName) {
    head(appName, "0.1")
    help("help") text("prints this usage text")
    opt[String]("trainLocation") required() action { (x,c) => c.copy(trainLocation=x) }
    opt[String]("testLocation") required() action { (x,c) => c.copy(testLocation=x) }
  }.parse(args, LinearPixelsConfig()).get

  /**
   * The actual driver receives its configuration parameters from spark-submit usually.
   * @param args
   */
  def main(args: Array[String]) = {
    val appConfig = parse(args)

    val conf = new SparkConf().setAppName(appName)
    conf.setIfMissing("spark.master", "local[2]") // This is a fallback if things aren't set via spark submit.
    val sc = new SparkContext(conf)
    run(sc, appConfig)

    sc.stop()
  }

}
