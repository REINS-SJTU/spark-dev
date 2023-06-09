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
package org.apache.spark.sql.mv.optimizer.rewrite.component.rewrite

import org.apache.spark.sql.catalyst.expressions.AttributeReference
import org.apache.spark.sql.catalyst.plans.logical.{Aggregate, LogicalPlan}
import org.apache.spark.sql.mv.optimizer.rewrite.rule.{LogicalPlanRewrite, RewriteContext, RewritedLeafLogicalPlan}

class GroupByRewrite(rewriteContext: RewriteContext) extends LogicalPlanRewrite {
  override def rewrite(plan: LogicalPlan): LogicalPlan = {

    val projectOrAggList = rewriteContext.viewLogicalPlan.get().tableLogicalPlan.output

    val newExpressions = _compensationExpressions.compensation.map { expr =>
      expr transformDown {
        case a@AttributeReference(name, dt, _, _) =>
          extractAttributeReferenceFromFirstLevel(projectOrAggList).filter(f => attributeReferenceEqual(a, f)).head
      }
    }


    val newPlan = plan transformDown {
      case Aggregate(_, aggregateExpressions, child) =>
        RewritedLeafLogicalPlan(Aggregate(newExpressions, aggregateExpressions, child))
    }
    _back(newPlan)
  }
}
