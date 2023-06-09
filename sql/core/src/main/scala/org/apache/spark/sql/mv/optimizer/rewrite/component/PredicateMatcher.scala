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
package org.apache.spark.sql.mv.optimizer.rewrite.component

import org.apache.spark.sql.catalyst.expressions.{Cast, EqualNullSafe, EqualTo, Expression, GreaterThan, GreaterThanOrEqual, LessThan, LessThanOrEqual, Literal}
import org.apache.spark.sql.mv.optimizer.rewrite.rule.{CompensationExpressions, ExpressionMatcher, RewriteContext, RewriteFail}
import org.apache.spark.sql.types.{DoubleType, FloatType, IntegerType, LongType, ShortType, StringType}

import scala.collection.mutable.ArrayBuffer;

class PredicateMatcher(rewriteContext: RewriteContext) extends ExpressionMatcher {

    override def compare: CompensationExpressions = {

        val compensationCond = ArrayBuffer[Expression]()

        if (rewriteContext.processedComponent.get().viewConjunctivePredicates.size > rewriteContext.processedComponent.get().queryConjunctivePredicates.size) return RewriteFail.PREDICATE_UNMATCH(this)

        // equal expression compare
        val viewEqual = extractEqualConditions(rewriteContext.processedComponent.get().viewConjunctivePredicates)
        val queryEqual = extractEqualConditions(rewriteContext.processedComponent.get().queryConjunctivePredicates)

        // if viewEqual are not subset of queryEqual, then it will not match.
        if (!isSubSetOf(viewEqual, queryEqual))
            return RewriteFail.PREDICATE_EQUALS_UNMATCH(this)
        compensationCond ++= subset[Expression](queryEqual, viewEqual)

        // less/greater expressions compare

        // make sure all less/greater expression with the same presentation
        // for example if exits a < 3 && a>=1 then we should change to RangeCondition(a,1,3)
        // or b < 3 then RangeCondition(b,None,3)
        val viewRange = extractRangeConditions(rewriteContext.processedComponent.get().viewConjunctivePredicates).map(RangeFilter.convertRangeCon)
        val queryRange = extractRangeConditions(rewriteContext.processedComponent.get().queryConjunctivePredicates).map(RangeFilter.convertRangeCon)

        // combine something like
        // RangeCondition(a,1,None),RangeCondition(a,None,3) into RangeCondition(a,1,3)


        val viewRangeCondition = RangeFilter.combineAndMergeRangeCondition(viewRange).toSeq
        val queryRangeCondtion = RangeFilter.combineAndMergeRangeCondition(queryRange).toSeq

        // again make sure viewRangeCondition.size is small queryRangeCondtion.size
        if (viewRangeCondition.size > queryRangeCondtion.size) return RewriteFail.PREDICATE_RANGE_UNMATCH(this)

        // all view rangeCondition  should a  SubRangeCondition of query
        val isRangeMatch = viewRangeCondition.map { viewRC =>
            if (queryRangeCondtion.filter(queryRC => queryRC.isSubRange(viewRC)).size >= 1) 1 else 0
        }.sum == viewRangeCondition.size

        if (!isRangeMatch)
            return RewriteFail.PREDICATE_RANGE_UNMATCH(this)
        compensationCond ++= queryRangeCondtion.flatMap(_.toExpression)

        // other conditions compare
        val viewResidual = extractResidualConditions(rewriteContext.processedComponent.get().viewConjunctivePredicates)
        val queryResidual = extractResidualConditions(rewriteContext.processedComponent.get().queryConjunctivePredicates)
        if (!isSubSetOf(viewResidual, queryResidual))
            return RewriteFail.PREDICATE_EXACLTY_SAME_UNMATCH(this)
        compensationCond ++= subset[Expression](queryResidual, viewResidual)

        // make sure all attributeReference in compensationCond is also in output of view
        // we get all columns without applied any function in projectList of viewCreateLogicalPlan
        val viewAttrs = extractAttributeReferenceFromFirstLevel(rewriteContext.viewLogicalPlan.get().viewCreateLogicalPlan.output)
        val compensationCondAllInViewProjectList = isSubSetOf(compensationCond.flatMap(extractAttributeReference), viewAttrs)
        if (!compensationCondAllInViewProjectList)
            return RewriteFail.PREDICATE_COLUMNS_NOT_IN_VIEW_PROJECT_OR_AGG(this)

        // return the compensation expressions
        CompensationExpressions(true, compensationCond)
    }


    def extractEqualConditions(conjunctivePredicates: Seq[Expression]) = {
        conjunctivePredicates.filter(RangeFilter.equalCon)
    }

    def extractRangeConditions(conjunctivePredicates: Seq[Expression]) = {
        conjunctivePredicates.filter(RangeFilter.rangeCon)
    }

    def extractResidualConditions(conjunctivePredicates: Seq[Expression]) = {
        conjunctivePredicates.filterNot(RangeFilter.equalCon).filterNot(RangeFilter.rangeCon)
    }
}

case class RangeCondition(key: Expression, lowerBound: Option[Literal], upperBound: Option[Literal],
                          includeLowerBound: Boolean,
                          includeUpperBound: Boolean) {

    def toExpression: Seq[Expression] = {
        (lowerBound, upperBound) match {
            case (None, None) => Seq()
            case (Some(l), None) => if (includeLowerBound)
                Seq(GreaterThanOrEqual(key, l)) else Seq(GreaterThan(key, Cast(l, key.dataType)))
            case (None, Some(l)) => if (includeUpperBound)
                Seq(LessThanOrEqual(key, l)) else Seq(LessThan(key, Cast(l, key.dataType)))
            case (Some(a), Some(b)) =>
                val aSeq = if (includeLowerBound)
                Seq(GreaterThanOrEqual(key, Cast(a, key.dataType))) else Seq(GreaterThan(key, Cast(a, key.dataType)))
                val bSeq = if (includeUpperBound)
                Seq(LessThanOrEqual(key, Cast(b, key.dataType))) else Seq(LessThan(key, Cast(b, key.dataType)))
                aSeq ++ bSeq
        }
    }

    def isSubRange(other: RangeCondition) = {
        this.key.semanticEquals(other.key) &&
                greaterThenOrEqual(this.lowerBound, other.lowerBound, true) &&
                greaterThenOrEqual(other.upperBound, this.upperBound, false)
    }

    def greaterThenOrEqual(lit1: Option[Literal], lit2: Option[Literal], isLowerBound: Boolean) = {
        (lit1, lit2) match {
            case (None, None) => true
            case (Some(l), None) => if (isLowerBound) true else false
            case (None, Some(l)) => if (isLowerBound) false else true
            case (Some(a), Some(b)) =>
                a.dataType match {

                case ShortType | IntegerType | LongType | FloatType | DoubleType => a.value.toString.toDouble >= b.value.toString.toDouble
                case StringType => a.value.toString >= b.value.toString
                case _ => throw new RuntimeException("not support type")
            }
        }
    }

    def +(other: RangeCondition) = {
        assert(this.key.semanticEquals(other.key))


        val _lowerBound = if (greaterThenOrEqual(this.lowerBound, other.lowerBound, true))
            (this.lowerBound, this.includeLowerBound) else (other.lowerBound, other.includeLowerBound)

        val _upperBound = if (greaterThenOrEqual(this.upperBound, other.upperBound, false))
            (other.upperBound, other.includeUpperBound) else (this.upperBound, this.includeUpperBound)
        RangeCondition(key, _lowerBound._1, _upperBound._1, _lowerBound._2, _upperBound._2)
    }


}

object RangeFilter {
    val equalCon = (f: Expression) => {
        f.isInstanceOf[EqualNullSafe] || f.isInstanceOf[EqualTo]
    }

    val convertRangeCon = (f: Expression) => {
        f match {
            case GreaterThan(a, Cast(v@Literal(_, _), _, _)) => RangeCondition(a, Option(v), None, false, false)
            case GreaterThan(a, v@Literal(_, _)) => RangeCondition(a, Option(v), None, false, false)
            case GreaterThan(v@Literal(_, _), a) => RangeCondition(a, None, Option(v), false, false)
            case GreaterThan(Cast(v@Literal(_, _), _, _), a) => RangeCondition(a, None, Option(v), false, false)
            case GreaterThanOrEqual(a, v@Literal(_, _)) => RangeCondition(a, Option(v), None, true, false)
            case GreaterThanOrEqual(a, Cast(v@Literal(_, _), _, _)) => RangeCondition(a, Option(v), None, true, false)
            case GreaterThanOrEqual(v@Literal(_, _), a) => RangeCondition(a, None, Option(v), false, true)
            case GreaterThanOrEqual(Cast(v@Literal(_, _), _, _), a) => RangeCondition(a, None, Option(v), false, true)
            case LessThan(a, Cast(v@Literal(_, _), _, _)) => RangeCondition(a, None, Option(v), false, false)
            case LessThan(a, v@Literal(_, _)) => RangeCondition(a, None, Option(v), false, false)
            case LessThan(Cast(v@Literal(_, _), _, _), a) => RangeCondition(a, Option(v), None, false, true)
            case LessThan(v@Literal(_, _), a) => RangeCondition(a, Option(v), None, false, true)
            case LessThanOrEqual(a, Cast(v@Literal(_, _), _, _)) => RangeCondition(a, None, Option(v), false, true)
            case LessThanOrEqual(a, v@Literal(_, _)) => RangeCondition(a, None, Option(v), false, true)
            // case LessThanOrEqual(a, Cast(v@Literal(_, _), _, _)) => RangeCondition(a, None, Option(v), false, true)
            case LessThanOrEqual(v@Literal(_, _), a) => RangeCondition(a, Option(v), None, true, false)
        }
    }

    val rangeCon = (f: Expression) => {
        f match {
            case GreaterThan(_, Literal(_, _)) | GreaterThan(Literal(_, _), _) => true
            case GreaterThan(_, Cast(Literal(_, _), _, _)) | GreaterThan(Cast(Literal(_, _), _, _), _) => true
            case GreaterThanOrEqual(_, Literal(_, _)) | GreaterThanOrEqual(Literal(_, _), _) => true
            case GreaterThanOrEqual(_, Cast(Literal(_, _), _, _)) | GreaterThanOrEqual(Cast(Literal(_, _), _, _), _) => true
            case LessThan(_, Literal(_, _)) | LessThan(Literal(_, _), _) => true
            case LessThan(_, Cast(Literal(_, _), _, _)) | LessThan(Cast(Literal(_, _), _, _), _) => true
            case LessThanOrEqual(_, Literal(_, _)) | LessThanOrEqual(Literal(_, _), _) => true
            case LessThanOrEqual(_, Cast(Literal(_, _), _, _)) | LessThanOrEqual(Cast(Literal(_, _), _, _), _) => true
            case _ => false
        }
    }

    def combineAndMergeRangeCondition(items: Seq[RangeCondition]) = {
        items.groupBy(f => f.key).map { f =>
            val first = f._2.head.copy(lowerBound = None, upperBound = None)
            f._2.foldLeft(first) { (result, item) =>
                result + item
            }
        }
    }
}



