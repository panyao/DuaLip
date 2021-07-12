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

import breeze.linalg.{DenseVector, SparseVector}
import breeze.numerics.{abs, inf}
import breeze.optimize.FirstOrderMinimizer.State
import breeze.optimize.{DiffFunction, LBFGSB => GenericLBFGSB}

import com.linkedin.dualip.util.IOUtility.{iterationLog, time}
import com.linkedin.dualip.util.{OptimizerState, Status}
import com.linkedin.dualip.util.Status.Status

import scala.collection.mutable

/**
  * A custom implementation of LBFGSB to solve a maximization problem with non-negativity constraints on the solution
  * @param maxIter is the maximum number of LBFGSB iterations to run
  * @param m       controls the size of the history used
  * @param dualTolerance  change in dual (tolerance) to decide convergence
  * @param slackTolerance change in max slack (tolerance) to decide convergence
  */
class LBFGSB(
  maxIter: Int = 100,
  m: Int = 50,
  dualTolerance: Double = 1e-8,
  slackTolerance: Double = 5e-6
) extends Serializable with DualPrimalGradientMaximizer {

  // The number of iterations to hold dual convergence after it has been reached
  // this trick is to ensure the calls from line search don't end up converging the optimizer
  val holdConvergenceForIter: Int = 10

  /**
    * Implementation of the gradient maximizer API for dual-primal solvers
    * @param f - objective function from dual-primal
    * @param initialValue
    * @param verbosity - control logging level
    * @return a tuple of (optimizedVariable, objective computation, number of iterations, log)
    */
  def maximize(f: DualPrimalDifferentiableObjective, initialValue: SparseVector[Double], verbosity: Int)
  : (SparseVector[Double], DualPrimalDifferentiableComputationResult, OptimizerState) = {
    val log = new StringBuilder()
    val lbfgs = new GenericLBFGSB(lowerBounds = DenseVector.zeros(initialValue.length),
      upperBounds = DenseVector.fill[Double](initialValue.length, inf),
      maxIter = maxIter, m = m)

    // Hold the results of the dual objective from the previous iteration to check convergence
    var lastResult: DualPrimalDifferentiableComputationResult = null
    var lastResultAtIter: Int = 0

    // LBFGS calls this function once before starting the optimization routine to initialize state,
    // we start from 0 and check convergence after iteration 1 to ensure we don't naively converge
    var i: Int = 0
    var status: Status = Status.Running

    val parameters: String = f"LBFGSB solver\nprimalUpperBound: ${f.getPrimalUpperBound}%.8e, maxIter: ${maxIter}, " +
      s"dualTolerance: ${dualTolerance} slackTolerance: ${slackTolerance}\n\n"
    print(parameters)
    log ++ parameters

    // To preserve the order of insertion
    val iLog = mutable.LinkedHashMap[String, String]()

    val gradient: DiffFunction[DenseVector[Double]] = new DiffFunction[DenseVector[Double]] {
      // artificially stop the optimizer when the custom convergence criteria has been hit
      val zeros: DenseVector[Double] = DenseVector.zeros(initialValue.length)
      def calculate(x: DenseVector[Double]): (Double, DenseVector[Double]) = {

        iLog.clear()
        iLog += ("iter" -> f"${i}%5d")

        // compute function at current dual value
        val result: DualPrimalDifferentiableComputationResult = time({
          f.calculate(SparseVector.apply(x.data), iLog, verbosity)
        }, iLog)

        // write the optimization parameters to a log file
        log ++= iterationLog(iLog)

        // check convergence and print statistics except for first iteration
        if (i > 1) {
          assert(lastResult != null, "last result should have been computed in the previous iteration")
          if (result.slackMetadata.maxSlack < slackTolerance && (i - lastResultAtIter) > holdConvergenceForIter) {
          //if ((i - lastResultAtIter) > holdConvergenceForIter) { // replace above line with this for Eclipse comparison
            status = Status.Converged
          }
        }
        // We record the last iteration me made a useful improvement to the objective so that we just check the number
        // of iterations since last useful improvement for convergence. Useful improvement just means the new objective
        // is better than the old by a certain level of tolerance.
        if (lastResult == null || (result.dualObjective - lastResult.dualObjective) / abs(lastResult.dualObjective) > dualTolerance) {
          lastResult = result
          lastResultAtIter = i
          // Check if the dual objective has exceeded the primal upper bound
          if (f.checkInfeasibility(result)) {
            status = Status.Infeasible
          }
        }
        i += 1
        if (status != Status.Running) {
          (-result.dualObjective, zeros)
        } else {
          // LBFGS routine is writen for a minimization problem but the dual is a maximization problem
          (-result.dualObjective, -result.dualGradient.toDenseVector)
        }
      }
    }
    try {
      val result: State[DenseVector[Double], _, _] = lbfgs.minimizeAndReturnState(gradient, initialValue.toDenseVector)
      val iterationMsg: String = s"Total LBFGS iterations: ${result.iter}\n"
      print(iterationMsg)
      log ++ iterationMsg
      if (result.iter >= maxIter)
        status = Status.Terminated
      else
        status = Status.Converged
      (SparseVector.apply(result.x.data), lastResult, OptimizerState(i, status, log.toString))
    } catch {
      case e: UnsupportedOperationException => {
        val iterationMsg: String = s"Failure because of non-differentiability after iterations: ${lastResultAtIter}\n"
        print(iterationMsg)
        log ++ iterationMsg
        status = Status.Failed
        (lastResult.lambda, lastResult, OptimizerState(i, status, log.toString))
      }
    }

  }
}