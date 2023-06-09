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

import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.plans.logical.{Filter, LogicalPlan, Project}
import org.apache.spark.sql.mv.MaterializedViewCatalyst
import org.apache.spark.sql.mv.optimizer.rewrite.component.rewrite.{PredicateRewrite, ProjectRewrite, TableOrViewRewrite}
import org.apache.spark.sql.mv.optimizer.rewrite.component.{PredicateMatcher, ProjectMatcher, TableNonOpMatcher}

object WithoutJoinGroupRule {
  def apply: WithoutJoinGroupRule = new WithoutJoinGroupRule()
}


class WithoutJoinGroupRule extends RewriteMatchRule {
  override def fetchView(plan: LogicalPlan, rewriteContext: RewriteContext): Seq[ViewLogicalPlan] = {
    require(plan.resolved, "LogicalPlan must be resolved.")

    if (isAggExistsExists(plan) || isJoinExists(plan)) return Seq()

    val tables = extractTablesFromPlan(plan)
    if (tables.isEmpty)
      return Seq()
    val table = tables.head
    val viewPlan = MaterializedViewCatalyst.getInstance().getCandidateViewsByTable(table) match {
      case Some(viewNames) =>
        viewNames.filter { viewName =>
          MaterializedViewCatalyst.getInstance().getViewCreateLogicalPlan(viewName) match {
            case Some(viewLogicalPlan) =>
              extractTablesFromPlan(viewLogicalPlan).toSet == Set(table)
            case None => false
          }
        }.map { targetViewName =>
          ViewLogicalPlan(
            MaterializedViewCatalyst.getInstance().getViewLogicalPlan(targetViewName).get,
            MaterializedViewCatalyst.getInstance().getViewCreateLogicalPlan(targetViewName).get)
        }.toSeq
      case None => Seq()
    }
    viewPlan
  }


  /**
   * query: select * from a,b where a.name=b.name and a.name2="jack" and b.jack="wow";
   * view: a_view= select * from a,b where a.name=b.name and a.name2="jack" ;
   * target: select * from a_view where  b.jack="wow"
   *
   * step 0: tables equal check
   * step 1: View equivalence classes:
   * query: PE:{a.name,b.name}
   * NPE: {a.name2="jack"} {b.jack="wow"}
   * view: PE: {a.name,b.name},NPE: {a.name2="jack"}
   *
   * step2 QPE < VPE, and QNPE < VNPE. We should normalize the PE make sure a=b equal to b=a, and
   * compare the NPE with Range Check, the others just check exactly
   *
   * step3: output columns check
   *
   * @param plan
   * @return
   */
  override def rewrite(plan: LogicalPlan, rewriteContext: RewriteContext): LogicalPlan = {
    val targetViewPlanOption = fetchView(plan, rewriteContext)
    if (targetViewPlanOption.isEmpty) return plan

    var shouldBreak = false
    var finalPlan = RewritedLogicalPlan(plan, true)

    targetViewPlanOption.foreach { targetViewPlan =>
      if (!shouldBreak) {
        rewriteContext.viewLogicalPlan.set(targetViewPlan)
        val res = _rewrite(plan, rewriteContext)
        res match {
          case a@RewritedLogicalPlan(_, true) =>
            finalPlan = a
          case a@RewritedLogicalPlan(_, false) =>
            finalPlan = a
            shouldBreak = true
        }
      }
    }
    finalPlan
  }

  def _rewrite(plan: LogicalPlan, rewriteContext: RewriteContext): LogicalPlan = {

    var queryConjunctivePredicates: Seq[Expression] = Seq()
    var viewConjunctivePredicates: Seq[Expression] = Seq()

    var queryProjectList: Seq[Expression] = Seq()
    var viewProjectList: Seq[Expression] = Seq()

    // check projectList and where condition
    normalizePlan(plan) match {
      case Project(projectList, Filter(condition, _)) =>
        queryConjunctivePredicates = splitConjunctivePredicates(condition)
        queryProjectList = projectList
      case Project(projectList, _) =>
        queryProjectList = projectList
    }

    normalizePlan(rewriteContext.viewLogicalPlan.get().viewCreateLogicalPlan) match {
      case Project(projectList, Filter(condition, _)) =>
        viewConjunctivePredicates = splitConjunctivePredicates(condition)
        viewProjectList = projectList
      case Project(projectList, _) =>
        viewProjectList = projectList
    }


    rewriteContext.processedComponent.set(ProcessedComponent(
      queryConjunctivePredicates,
      viewConjunctivePredicates,
      queryProjectList,
      viewProjectList,
      Seq(),
      Seq(),
      Seq(),
      Seq(),
      Seq(),
      Seq()
    ))

    /**
     * Three match/rewrite steps:
     *   1. Predicate
     *   2. Project
     *   3. Table(View)
     */
    val pipeline = buildPipeline(rewriteContext: RewriteContext, Seq(
      new PredicateMatcher(rewriteContext),
      new PredicateRewrite(rewriteContext),
      new ProjectMatcher(rewriteContext),
      new ProjectRewrite(rewriteContext),
      new TableNonOpMatcher(rewriteContext),
      new TableOrViewRewrite(rewriteContext)
    ))

    /**
     * When we are rewriting plan, any step fails, we should return the original plan.
     * So we should check the mark in RewritedLogicalPlan is final success or fail.
     */
    LogicalPlanRewritePipeline(pipeline).rewrite(plan)
  }
}


