/*
 * Copyright 2010 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.compile;

import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.phoenix.compile.GroupByCompiler.GroupBy;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.exception.SQLExceptionInfo;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.expression.LiteralExpression;
import org.apache.phoenix.parse.AddParseNode;
import org.apache.phoenix.parse.AndParseNode;
import org.apache.phoenix.parse.BetweenParseNode;
import org.apache.phoenix.parse.CaseParseNode;
import org.apache.phoenix.parse.ColumnParseNode;
import org.apache.phoenix.parse.ComparisonParseNode;
import org.apache.phoenix.parse.DivideParseNode;
import org.apache.phoenix.parse.FunctionParseNode;
import org.apache.phoenix.parse.IsNullParseNode;
import org.apache.phoenix.parse.MultiplyParseNode;
import org.apache.phoenix.parse.OrParseNode;
import org.apache.phoenix.parse.ParseNode;
import org.apache.phoenix.parse.SelectStatement;
import org.apache.phoenix.parse.SelectStatementRewriter;
import org.apache.phoenix.parse.SubtractParseNode;
import org.apache.phoenix.parse.TraverseNoParseNodeVisitor;
import org.apache.phoenix.schema.ColumnRef;
import org.apache.phoenix.schema.PDataType;
import org.apache.phoenix.schema.TypeMismatchException;


public class HavingCompiler {

    private HavingCompiler() {
    }

    public static Expression compile(StatementContext context, SelectStatement statement, GroupBy groupBy) throws SQLException {
        ParseNode having = statement.getHaving();
        if (having == null) {
            return null;
        }
        ExpressionCompiler expressionBuilder = new ExpressionCompiler(context, groupBy);
        Expression expression = having.accept(expressionBuilder);
        if (expression.getDataType() != PDataType.BOOLEAN) {
            throw new TypeMismatchException(PDataType.BOOLEAN, expression.getDataType(), expression.toString());
        }
        if (LiteralExpression.FALSE_EXPRESSION == expression) {
            context.setScanRanges(ScanRanges.NOTHING);
            return null;
        } else if (LiteralExpression.TRUE_EXPRESSION == expression) {
            return null;
        }
        if (!expressionBuilder.isAggregate()) {
            throw new SQLExceptionInfo.Builder(SQLExceptionCode.ONLY_AGGREGATE_IN_HAVING_CLAUSE).build().buildException();
        }
        return expression;
    }

    public static SelectStatement rewrite(StatementContext context, SelectStatement statement, GroupBy groupBy) throws SQLException {
        ParseNode having = statement.getHaving();
        if (having == null) {
            return statement;
        }
        HavingClauseVisitor visitor = new HavingClauseVisitor(context, groupBy);
        having.accept(visitor);
        statement = SelectStatementRewriter.moveFromHavingToWhereClause(statement, visitor.getMoveToWhereClauseExpressions());
        return statement;
    }

    /**
     * 
     * Visitor that figures out if an expression can be moved from the HAVING clause to
     * the WHERE clause, since it's more optimal to pre-filter instead of post-filter.
     * 
     * The visitor traverses through AND expressions only and into comparison expresssions.
     * If a comparison expression uses a GROUP BY column and does not use any aggregate
     * functions, then it's moved. For example, these HAVING expressions would be moved:
     * 
     * select count(1) from atable group by a_string having a_string = 'foo'
     * select count(1) from atable group by a_date having round(a_date,'hour') > ?
     * select count(1) from atable group by a_date,a_string having a_date > ? or a_string = 'a'
     * select count(1) from atable group by a_string,b_string having a_string = 'a' and b_string = 'b'
     * 
     * while these would not be moved:
     * 
     * select count(1) from atable having min(a_integer) < 5
     * select count(1) from atable group by a_string having count(a_string) >= 1
     * select count(1) from atable group by a_date,a_string having a_date > ? or min(a_string) = 'a'
     * select count(1) from atable group by a_date having round(min(a_date),'hour') < ?
     *
     * @author jtaylor
     * @since 0.1
     */
    private static class HavingClauseVisitor extends TraverseNoParseNodeVisitor<Void> {
        private ParseNode topNode = null;
        private boolean hasNoAggregateFunctions = true;
        private Boolean hasOnlyAggregateColumns;
        private final StatementContext context;
        private final GroupBy groupBy;
        private final Set<ParseNode> moveToWhereClause = new LinkedHashSet<ParseNode>();
        
        HavingClauseVisitor(StatementContext context, GroupBy groupBy) {
            this.context = context;
            this.groupBy = groupBy;
        }
        
        public Set<ParseNode> getMoveToWhereClauseExpressions() {
            return moveToWhereClause;
        }
        
        @Override
        public boolean visitEnter(AndParseNode node) throws SQLException {
            return true;
        }
        
        @Override
        public boolean visitEnter(OrParseNode node) throws SQLException {
            enterBooleanNode(node);
            return true;
        }
        
        @Override
        public boolean visitEnter(ComparisonParseNode node) throws SQLException {
            enterBooleanNode(node);
            return true;
        }
        
        @Override
        public boolean visitEnter(IsNullParseNode node) throws SQLException {
            enterBooleanNode(node);
            return true;
        }
        
        private void enterBooleanNode(ParseNode node) {
            if (topNode == null) {
                topNode = node;
            }
        }
        
        private void leaveBooleanNode(ParseNode node) {
            if (topNode == node) {
                if ( hasNoAggregateFunctions && !Boolean.FALSE.equals(hasOnlyAggregateColumns)) {
                    moveToWhereClause.add(node);
                }
                hasNoAggregateFunctions = true;
                hasOnlyAggregateColumns = null;
                topNode = null;
            }
        }

        @Override
        public Void visitLeave(OrParseNode node, List<Void> l) throws SQLException {
            leaveBooleanNode(node);
            return null;
        }

        @Override
        public Void visitLeave(ComparisonParseNode node, List<Void> l) throws SQLException {
            leaveBooleanNode(node);
            return null;
        }

        @Override
        public Void visitLeave(IsNullParseNode node, List<Void> l) throws SQLException {
            leaveBooleanNode(node);
            return null;
        }

        @Override
        public boolean visitEnter(FunctionParseNode node) throws SQLException {
            boolean isAggregate = node.isAggregate();
            this.hasNoAggregateFunctions = this.hasNoAggregateFunctions && !isAggregate;
            return !isAggregate;
        }

        @Override
        public boolean visitEnter(CaseParseNode node) throws SQLException {
            return true;
        }

        @Override
        public Void visit(ColumnParseNode node) throws SQLException {
            ColumnRef ref = context.getResolver().resolveColumn(node.getSchemaName(), node.getTableName(), node.getName());
            boolean isAggregateColumn = groupBy.getExpressions().indexOf(ref.newColumnExpression()) >= 0;
            if (hasOnlyAggregateColumns == null) {
                hasOnlyAggregateColumns = isAggregateColumn;
            } else {
                hasOnlyAggregateColumns &= isAggregateColumn;
            }
            
            return null;
        }

        @Override
        public boolean visitEnter(SubtractParseNode node) throws SQLException {
            return true;
        }

        @Override
        public boolean visitEnter(AddParseNode node) throws SQLException {
            return true;
        }

        @Override
        public boolean visitEnter(MultiplyParseNode node) throws SQLException {
            return true;
        }

        @Override
        public boolean visitEnter(DivideParseNode node) throws SQLException {
            return true;
        }

        @Override
        public boolean visitEnter(BetweenParseNode node) throws SQLException {
            return true;
        }
    }
}