package io.bittiger.movierating.hollywood

import java.io.File
import java.net.URL

import org.apache.spark.rdd._
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.mllib.recommendation.{ALS, MatrixFactorizationModel, Rating}
//import org.apache.spark.sql.SQLContext


import scala.sys.process._


/**
  * Created by rwang on 6/15/16.
  *
  * small_dataset_url = "http://files.grouplens.org/datasets/movielens/ml-latest-small.zip"
  * complete_dataset_url = "http://files.grouplens.org/datasets/movielens/ml-latest.zip"
  *
  */
object MovieRater extends App {

  //  if (args.length != 2) {
  //    println("Usage: /path/to/spark/bin/spark-submit --driver-memory 2g --class MovieLensALS " +
  //      "target/scala-*/movielens-als-ssembly-*.jar movieLensHomeDir personalRatingsFile")
  //    sys.exit(1)
  //  }

  // set up environment
  val conf = new SparkConf().setAppName("MovieRater")
//    .set("spark.executor.memory", "2g")
  val sc = new SparkContext(conf)
//  val sqlContext = new SQLContext(sc)

  val ratingFilePath = args(0)
//  val movieFilePath = args(1)

  // load ratings and movie titles, and parse
  val ratingsData = sc.textFile(ratingFilePath)
  //remove header
  val noHeader = ratingsData.mapPartitionsWithIndex { (idx, iter) => if (idx == 0) iter.drop(1) else iter }
  val ratingsRDD = noHeader.map(_.split(',') match { case Array(user, movie, rating, timestamp) =>
    Rating(user.toInt, movie.toInt, rating.toDouble)
  })

  val numRatings = ratingsRDD.count()
  val numUsers = ratingsRDD.map(_.user).distinct().count()
  val numMovies = ratingsRDD.map(_.product).distinct().count()

  println("Got " + numRatings + " ratings from " + numUsers
    + " users on " + numMovies + " movies.")

  //split into 3 for training, validation, and testing
  val splitArr = ratingsRDD.randomSplit(Array(6, 2, 2), 0L)
  val trainingRDD = splitArr(0)
  val validationRDD = splitArr(1)
  val testRDD = splitArr(2)

  val numTraining = trainingRDD.count()
  val numValidation = validationRDD.count()
  val numTest = testRDD.count()

  println("Training: " + numTraining + ", validation: " + numValidation + ", test: " + numTest)

  // train models and evaluate them on the validation set

  val ranks = List(8, 12)
  val lambdas = List(0.1, 10.0)
  val numIters = List(10, 20)
  var bestModel: Option[MatrixFactorizationModel] = None
  var bestValidationRmse = Double.MaxValue
  var bestRank = 0
  var bestLambda = -1.0
  var bestNumIter = -1
  for (rank <- ranks; lambda <- lambdas; numIter <- numIters) {
    val model = ALS.train(trainingRDD, rank, numIter, lambda)
    val validationRmse = computeRmse(model, validationRDD, numValidation)
    println("RMSE (validation) = " + validationRmse + " for the model trained with rank = "
      + rank + ", lambda = " + lambda + ", and numIter = " + numIter + ".")
    if (validationRmse < bestValidationRmse) {
      bestModel = Some(model)
      bestValidationRmse = validationRmse
      bestRank = rank
      bestLambda = lambda
      bestNumIter = numIter
    }
  }

  // evaluate the best model on the test set
  val testRmse = computeRmse(bestModel.get, testRDD, numTest)
  println("The best model was trained with rank = " + bestRank + " and lambda = " + bestLambda
    + ", and numIter = " + bestNumIter + ", and its RMSE on the test set is " + testRmse + ".")

  // create a naive baseline and compare it with the best model

  val meanRating = trainingRDD.union(validationRDD).map(_.rating).mean
  val baselineRmse =
    math.sqrt(testRDD.map(x => (meanRating - x.rating) * (meanRating - x.rating)).mean)
  val improvement = (baselineRmse - testRmse) / baselineRmse * 100
  println("The best model improves the baseline by " + "%1.2f".format(improvement) + "%.")


  /** Compute RMSE (Root Mean Squared Error). */
  def computeRmse(model: MatrixFactorizationModel, data: RDD[Rating], n: Long): Double = {
    val predictions: RDD[Rating] = model.predict(data.map(x => (x.user, x.product)))
    val predictionsAndRatings = predictions.map(x => ((x.user, x.product), x.rating))
      .join(data.map(x => ((x.user, x.product), x.rating)))
      .values
    math.sqrt(predictionsAndRatings.map(x => (x._1 - x._2) * (x._1 - x._2)).reduce(_ + _) / n)
  }


}