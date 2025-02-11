/*
 * BSD 2-CLAUSE LICENSE
 *
 * Copyright 2021 LinkedIn Corporation
 * All Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 */
 
package com.linkedin.dualip.solver

import breeze.linalg.{SparseVector => BSV}
import com.linkedin.dualip.blas.VectorOperations
import com.linkedin.dualip.util.{DataFormat, InputPaths, ProjectionType, SolverUtility}
import com.linkedin.dualip.util.DataFormat.{AVRO, DataFormat}
import com.linkedin.dualip.util.IOUtility.{readDataFrame, saveDataFrame, saveLog, printCommandLineArgs}
import com.linkedin.dualip.util.ProjectionType.{Greedy, ProjectionType, Simplex}
import org.apache.log4j.Logger
import org.apache.spark.sql.{DataFrame, SparkSession}

import scala.collection.mutable

case class IndexValuePair(index: Int, value: Double)

/**
 * Entry point to LP solver library. It initializes the necessary components:
 *   - objective function
 *   - optimizer
 *   - initial value of lambda
 *   - output parameters
 * and runs the solver
 */
object LPSolverDriver {

  val logger: Logger = Logger.getLogger(getClass)

  /**
   * Save solution. The method saves logs, some statistics of the solution and the dual variable
   * Note: primal is optional because not all problems support it yet
   *       The schema of the primal depends on the problem.
   * @param outputPath
   * @param lambda
   * @param objectiveValue
   * @param primal
   * @param log
   * @param spark
   */
  def saveSolution(
    outputPath: String,
    outputFormat: DataFormat,
    lambda: BSV[Double],
    objectiveValue: DualPrimalDifferentiableComputationResult,
    primal: Option[DataFrame],
    log: String)(implicit spark: SparkSession): Unit = {

    import spark.implicits._

    // define relative paths
    val logPath = outputPath + "/log/log.txt"
    val dualPath = outputPath + "/dual"
    val violationPath = outputPath + "/violation"
    val primalPath = outputPath + "/primal"

    // write log to a text file
    saveLog(log, logPath)

    val dualDF = lambda.activeIterator.toList.toDF("index", "value")
    saveDataFrame(dualDF, dualPath, outputFormat, Option(1))
    val violationDF = objectiveValue.constraintsSlack.activeIterator.toList.toDF("index", "value")
    saveDataFrame(violationDF, violationPath, outputFormat, Option(1))
    primal.foreach(saveDataFrame(_, primalPath, outputFormat))
  }

  /**
   * Load initial lambda value for warm restarts
   * @param path
   * @param format
   * @param size - the vector is sparse, so we need to provide its dimensionality
   * @param spark
   * @return
   */
  def loadInitialLambda(path: String, format: DataFormat, size: Int)(implicit spark: SparkSession): BSV[Double] = {
    import spark.implicits._
    val (idx, vals) = readDataFrame(path, format).as[IndexValuePair]
      .collect()
      .sortBy(_.index)
      .map(x => (x.index, x.value))
      .unzip
    new BSV(idx, vals, size)
  }

  /**
   * Optionally load initial lambda, otherwise initialize with zeros
   * @param initialLambdaPath
   * @param lambdaFormat
   * @param lambdaDim
   */
  def getInitialLambda(initialLambdaPath: Option[String], lambdaFormat: DataFormat, lambdaDim: Int)
    (implicit spark: SparkSession): BSV[Double] = {
    initialLambdaPath.map { path =>
      loadInitialLambda(path, lambdaFormat, lambdaDim)
    }.getOrElse(BSV.zeros[Double](lambdaDim))
  }

  /**
   * Solve the LP and save output
   * @param objectiveFunction
   * @param solver
   * @param initialLambda
   * @param driverParams
   * @param spark
   */
  def solve(
    objectiveFunction: DualPrimalDifferentiableObjective,
    solver: DualPrimalGradientMaximizer,
    initialLambda: BSV[Double],
    driverParams: LPSolverDriverParams)
    (implicit spark: SparkSession): DualPrimalDifferentiableComputationResult = {

    val (lambda, objectiveValue, state) = solver.maximize(objectiveFunction, initialLambda, driverParams.verbosity)

    val activeConstraints = VectorOperations.countNonZeros(lambda)
    // Print end of iteration information
    val finalLogMessage = f"Status: ${state.status}\n" +
      f"Total number of iterations: ${state.iterations}\n" +
      f"Primal: ${objectiveValue.primalObjective}%.8e\n" +
      f"Dual: ${objectiveValue.dualObjective}%.8e\n" +
      f"Number of Active Constraints: ${activeConstraints}"
    println(finalLogMessage)

    // optionally save primal (in case the solver supports it)
    val primalToSave: Option[DataFrame] = if(driverParams.savePrimal) {
      val primal = objectiveFunction.getPrimalForSaving(lambda)
      if (primal.isEmpty) {
        logger.warn("Objective function does not support primal saving, skipping.")
      }
      primal
    } else {
      // we chose not to save primal (even if it is supported)
      None
    }

    saveSolution(driverParams.solverOutputPath, driverParams.outputFormat, lambda,
      objectiveValue, primalToSave, state.log + finalLogMessage)
    objectiveValue
  }

  /**
   * Initializes the objective function based on class name. All objective specific parameters are pulled from
   * the command line arguments. The companion object of the objective function is expected to implement
   * the DualPrimalObjectiveLoader trait
   * @param className class name of the objective function
   * @param gamma     regularization parameter
   * @param projectionType used to encode simple constraints on the objective
   * @param args      passthrough command line arguments for objective-specific initializations
   * @param spark
   * @return
   */
  def loadObjectiveFunction(className: String, gamma: Double, projectionType: ProjectionType, args: Array[String])(implicit spark: SparkSession): DualPrimalDifferentiableObjective = {
    try {
      Class.forName(className + "$")
        .getField("MODULE$").get(None.orNull).asInstanceOf[DualPrimalObjectiveLoader].apply(gamma, projectionType, args)
    } catch {
      case e: ClassNotFoundException => {
        val errorMessage = s"Error initializing objective function loader $className.\n" +
        "Please provide a fully qualified companion object name (including namespace) that implements DualPrimalObjectiveLoader trait.\n" +
          e.toString
        sys.error(errorMessage)
      }
      case e: Exception => {
        sys.error(e.toString)
      }
    }
  }

  /**
   * Run the solver with a fixed gamma and given optimizer, as opposed to adpative smoothing algorithm.
   */
  def singleRun(driverParams: LPSolverDriverParams, inputParams: InputPaths, args: Array[String], fastSolver: Option[DualPrimalGradientMaximizer])
    (implicit spark: SparkSession): DualPrimalDifferentiableComputationResult = {

    val solver: DualPrimalGradientMaximizer = if (fastSolver.isEmpty) {
      DualPrimalGradientMaximizerLoader.load(args)
    } else {
      fastSolver.get
    }
    val objective: DualPrimalDifferentiableObjective = loadObjectiveFunction(driverParams.objectiveClass,
      driverParams.gamma, driverParams.projectionType, args)

    val initialLambda: BSV[Double] = getInitialLambda(driverParams.initialLambdaPath, inputParams.format, objective.dualDimensionality)
    val solution = solve(objective, solver, initialLambda, driverParams)
    solution
  }

  /**
   * Entry point to spark job
   * @param args
   */
  def main(args: Array[String]): Unit = {

    implicit val spark = SparkSession
      .builder()
      .appName(getClass.getSimpleName)
      .getOrCreate()

    try {
      val driverParams = LPSolverDriverParamsParser.parseArgs(args)
      val inputParams = InputPathParamsParser.parseArgs(args)

      println("-----------------------------------------------------------------------")
      println("                      DuaLip v1.0     2021")
      println("            Dual Decomposition based Linear Program Solver")
      println("-----------------------------------------------------------------------")
      println("Settings:")
      printCommandLineArgs(args)
      println("")
      print("Optimizer: ")
  	  
  	  singleRun(driverParams, inputParams, args, None)
  	  
    } catch {
      case other: Exception => sys.error("Got an exception: " + other)
    } finally {
      spark.stop()
    }
  }
}

/**
 * @param initialLambdaPath  Optional path to initialize dual variables for algorithm restarts
 * @param gamma              Coefficient for quadratic objective regularizer, used by most objectives
 * @param objectiveClass     Objective function implementation
 * @param outputFormat       The format of output, can be AVRO or ORC
 * @param savePrimal         Flag to save primal
 * @param verbosity          0: Concise logging. 1: log with increased verbosity. 2: log everything
 * @param solverOutputPath   The outputPath
 */
case class LPSolverDriverParams(
  initialLambdaPath: Option[String] = None,
  gamma: Double = 1E-3,
  projectionType: ProjectionType = Simplex,
  objectiveClass: String = "",
  outputFormat: DataFormat = AVRO,
  savePrimal: Boolean = false,
  verbosity: Int = 1,
  solverOutputPath: String = ""
)

/**
 * Driver parameters parser
 */
object LPSolverDriverParamsParser {
  def parseArgs(args: Array[String]): LPSolverDriverParams = {
    val parser = new scopt.OptionParser[LPSolverDriverParams]("Parsing solver parameters") {
      override def errorOnUnknownArgument = false

      opt[String]("driver.initialLambdaPath") optional() action { (x, c) => c.copy(initialLambdaPath = Option(x)) }
      opt[Double]("driver.gamma") optional() action { (x, c) => c.copy(gamma = x) }
      opt[String]("driver.projectionType") required() action { (x, c) => c.copy(projectionType = ProjectionType.withName(x)) }
      opt[String]("driver.objectiveClass") required() action { (x, c) => c.copy(objectiveClass = x) }
      opt[String]("driver.outputFormat") optional() action { (x, c) => c.copy(outputFormat = DataFormat.withName(x)) }
      opt[Boolean]("driver.savePrimal") optional() action { (x, c) => c.copy(savePrimal = x) }
      opt[Int]("driver.verbosity") optional() action { (x, c) => c.copy(verbosity = x) }
      opt[String]("driver.solverOutputPath") required() action { (x, c) => c.copy(solverOutputPath = x) }
    }

    parser.parse(args, LPSolverDriverParams()) match {
      case Some(params) => params
      case _ => throw new IllegalArgumentException(s"Parsing the command line arguments ${args.mkString(", ")} failed")
    }
  }
}

/**
 * Input parameter parser. These are generic input parameters that are shared by most solvers now.
 */
object InputPathParamsParser {
  def parseArgs(args: Array[String]): InputPaths = {
    val parser = new scopt.OptionParser[InputPaths]("Input data parameters parser") {
      override def errorOnUnknownArgument = false

      opt[String]("input.ACblocksPath") required() action { (x, c) => c.copy(ACblocksPath = x) }
      opt[String]("input.vectorBPath") required() action { (x, c) => c.copy(vectorBPath = x) }
      opt[String]("input.format") required() action { (x, c) => c.copy(format = DataFormat.withName(x)) }
    }

    parser.parse(args, InputPaths("", "", "", DataFormat.AVRO)) match {
      case Some(params) => params
      case _ => throw new IllegalArgumentException(s"Parsing the command line arguments ${args.mkString(", ")} failed")
    }
  }
}