/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.mv.optimizer.rewrite.rule

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference, Expression}
import org.apache.spark.sql.catalyst.plans.logical.{Join, LogicalPlan}
import org.apache.spark.sql.mv.optimizer.RewriteHelper

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable

trait RewriteMatchRule extends RewriteHelper {
  def fetchView(plan: LogicalPlan, rewriteContext: RewriteContext): Seq[ViewLogicalPlan]

  def rewrite(plan: LogicalPlan, rewriteContext: RewriteContext): LogicalPlan

  def buildPipeline[_](rewriteContext: RewriteContext, items: Seq[MatchOrRewrite]) = {
    val pipeline = mutable.ArrayBuffer[PipelineItemExecutor]()
    items.grouped(2).foreach { items =>
      pipeline += PipelineItemExecutor(items(0).asInstanceOf[ExpressionMatcher], items(1).asInstanceOf[LogicalPlanRewrite])
    }
    pipeline
  }
}

trait MatchOrRewrite {
  def rewrite(plan: LogicalPlan): LogicalPlan = {
    plan
  }

  def compare: CompensationExpressions = {
    CompensationExpressions(false, Seq())
  }
}

trait LogicalPlanRewrite extends MatchOrRewrite with RewriteHelper {
  protected var _compensationExpressions: CompensationExpressions = null

  def compensationExpressions(ce: CompensationExpressions) = {
    _compensationExpressions = ce
    this
  }

  def _back(newPlan: LogicalPlan) = {
    newPlan transformDown {
      case RewritedLeafLogicalPlan(inner) => inner
    }
  }

  def rewrite(plan: LogicalPlan): LogicalPlan
}

trait ExpressionMatcher extends MatchOrRewrite with ExpressionMatcherHelper {
  var rewriteFail: Option[RewriteFail] = None

  def compare: CompensationExpressions
}

object RewriteFail {
  val DEFAULT = CompensationExpressions(false, Seq())

  def apply(msg: String): RewriteFail = RewriteFail(msg, DEFAULT)

  def msg(value: String, matcher: ExpressionMatcher) = {
    matcher.rewriteFail = Option(apply(value))
    DEFAULT
  }

  def GROUP_BY_SIZE_UNMATCH(matcher: ExpressionMatcher) = {
    msg("GROUP_BY_SIZE_UNMATCH", matcher)
  }

  def GROUP_BY_COLUMNS_NOT_IN_VIEW_PROJECT_OR_AGG(matcher: ExpressionMatcher) = {
    msg("GROUP_BY_COLUMNS_NOT_IN_VIEW_PROJECT_OR_AGG", matcher)
  }

  def AGG_NUMBER_UNMATCH(matcher: ExpressionMatcher) = {
    msg("AGG_UNMATCH", matcher)
  }

  def AGG_COLUMNS_UNMATCH(matcher: ExpressionMatcher) = {
    msg("AGG_COLUMNS_UNMATCH", matcher)
  }

  def AGG_VIEW_MISSING_COUNTING_STAR(matcher: ExpressionMatcher) = {
    msg("AGG_VIEW_MISSING_COUNTING_STAR", matcher)
  }

  def JOIN_UNMATCH(matcher: ExpressionMatcher) = {
    msg("JOIN_UNMATCH", matcher)
  }

  def PREDICATE_UNMATCH(matcher: ExpressionMatcher) = {
    msg("PREDICATE_UNMATCH", matcher)
  }

  def PREDICATE_EQUALS_UNMATCH(matcher: ExpressionMatcher) = {
    msg("PREDICATE_EQUALS_UNMATCH", matcher)
  }

  def PREDICATE_RANGE_UNMATCH(matcher: ExpressionMatcher) = {
    msg("PREDICATE_RANGE_UNMATCH", matcher)
  }

  def PREDICATE_EXACLTY_SAME_UNMATCH(matcher: ExpressionMatcher) = {
    msg("PREDICATE_EXACTLY_SAME_UNMATCH", matcher)
  }

  def PREDICATE_COLUMNS_NOT_IN_VIEW_PROJECT_OR_AGG(matcher: ExpressionMatcher) = {
    msg("PREDICATE_COLUMNS_NOT_IN_VIEW_PROJECT_OR_AGG", matcher)
  }

  def PROJECT_UNMATCH(matcher: ExpressionMatcher) = {
    msg("PREDICATE_COLUMNS_NOT_IN_VIEW_PROJECT_OR_AGG", matcher)
  }
}


case class RewriteFail(val msg: String, val ce: CompensationExpressions)


trait ExpressionMatcherHelper extends MatchOrRewrite with RewriteHelper {
  def isSubSetOf(e1: Seq[Expression], e2: Seq[Expression]) = {
    e1.map { item1 =>
      e2.map { item2 =>
        if (item2.semanticEquals(item1)) 1 else 0
      }.sum
    }.sum == e1.size
  }

  def isSubSetOfWithOrder(e1: Seq[Expression], e2: Seq[Expression]) = {
    val zipCount = Math.min(e1.size, e2.size)
    (0 until zipCount).map { index =>
      if (e1(index).semanticEquals(e2(index)))
        0
      else 1
    }.sum == 0
  }

  def subset[T](e1: Seq[T], e2: Seq[T]) = {
    assert(e1.size >= e2.size)
    if (e1.size == 0) Seq[Expression]()
    e1.slice(e2.size, e1.size)
  }
}

case class CompensationExpressions(isRewriteSuccess: Boolean, compensation: Seq[Expression])

class LogicalPlanRewritePipeline(pipeline: Seq[PipelineItemExecutor]) extends Logging {
  def rewrite(plan: LogicalPlan): LogicalPlan = {

    var planRewrite: RewritedLogicalPlan = RewritedLogicalPlan(plan, false)

    (0 until pipeline.size).foreach { index =>

      if (!planRewrite.stopPipeline) {
        val start = System.currentTimeMillis()
        pipeline(index).execute(planRewrite) match {
          case a@RewritedLogicalPlan(_, true) =>
            logInfo(s"Pipeline item [${pipeline(index)}] fails. ")
            planRewrite = a
          case a@RewritedLogicalPlan(_, false) =>
            planRewrite = a
        }
        logWarning("pipeline index:" + index + ". " + String.valueOf(System.currentTimeMillis() - start))
      }
    }
    planRewrite
  }
}

object LogicalPlanRewritePipeline {
  def apply(pipeline: Seq[PipelineItemExecutor]): LogicalPlanRewritePipeline = new LogicalPlanRewritePipeline(pipeline)
}

case class PipelineItemExecutor(matcher: ExpressionMatcher, reWriter: LogicalPlanRewrite) extends Logging {
  def execute(plan: LogicalPlan) = {
    val compsation = matcher.compare
    compsation match {
      case CompensationExpressions(true, _) =>
        reWriter.compensationExpressions(compsation)
        reWriter.rewrite(plan)
      case CompensationExpressions(false, _) =>
        logWarning(s"=====Rewrite fail:${matcher.rewriteFail.map(_.msg).getOrElse("NONE")}=====")
        println(s"=====Rewrite fail:${matcher.rewriteFail.map(_.msg).getOrElse("NONE")}=====")
        RewritedLogicalPlan(plan, stopPipeline = true)
    }
  }
}

case class RewritedLogicalPlan(inner: LogicalPlan, val stopPipeline: Boolean = false) extends LogicalPlan {
  override def output: Seq[Attribute] = inner.output

  override def children: Seq[LogicalPlan] = Seq(inner)

}

case class RewritedLeafLogicalPlan(inner: LogicalPlan) extends LogicalPlan {
  override def output: Seq[Attribute] = Seq()

  override def children: Seq[LogicalPlan] = Seq()
}

case class ViewLogicalPlan(tableLogicalPlan: LogicalPlan, viewCreateLogicalPlan: LogicalPlan)

case class RewriteContext(viewLogicalPlan: AtomicReference[ViewLogicalPlan],
                          processedComponent: AtomicReference[ProcessedComponent],
                          replacedARMapping: mutable.HashMap[AttributeReference, AttributeReference] =
                          mutable.HashMap[AttributeReference, AttributeReference]())

case class ProcessedComponent(
                               queryConjunctivePredicates: Seq[Expression] = Seq(),
                               viewConjunctivePredicates: Seq[Expression] = Seq(),
                               queryProjectList: Seq[Expression] = Seq(),
                               viewProjectList: Seq[Expression] = Seq(),
                               queryGroupingExpressions: Seq[Expression] = Seq(),
                               viewGroupingExpressions: Seq[Expression] = Seq(),
                               queryAggregateExpressions: Seq[Expression] = Seq(),
                               viewAggregateExpressions: Seq[Expression] = Seq(),
                               viewJoins: Seq[Join] = Seq(),
                               queryJoins: Seq[Join] = Seq()
                             )


