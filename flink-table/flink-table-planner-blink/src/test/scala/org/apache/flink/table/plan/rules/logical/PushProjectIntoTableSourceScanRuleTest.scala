/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.plan.rules.logical

import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.RowTypeInfo
import org.apache.flink.table.api.{DataTypes, TableSchema, Types}
import org.apache.flink.table.plan.optimize.program.{FlinkBatchProgram, FlinkHepRuleSetProgramBuilder, HEP_RULES_EXECUTION_TYPE}
import org.apache.flink.table.types.TypeInfoDataTypeConverter
import org.apache.flink.table.util.{TableTestBase, TestNestedProjectableTableSource, TestProjectableTableSource}
import org.apache.flink.types.Row

import org.apache.calcite.plan.hep.HepMatchOrder
import org.apache.calcite.tools.RuleSets
import org.junit.{Before, Test}

/**
  * Test for [[PushProjectIntoTableSourceScanRule]].
  */
class PushProjectIntoTableSourceScanRuleTest extends TableTestBase {

  private val util = batchTestUtil()

  @Before
  def setup(): Unit = {
    util.buildBatchProgram(FlinkBatchProgram.DEFAULT_REWRITE)
    util.tableEnv.getConfig.getCalciteConfig.getBatchProgram.get.addLast(
      "rules",
      FlinkHepRuleSetProgramBuilder.newBuilder
        .setHepRulesExecutionType(HEP_RULES_EXECUTION_TYPE.RULE_SEQUENCE)
        .setHepMatchOrder(HepMatchOrder.BOTTOM_UP)
        .add(RuleSets.ofList(PushProjectIntoTableSourceScanRule.INSTANCE))
        .build()
    )

    val tableSchema = TableSchema.builder().fields(
      Array("a", "b", "c"),
      Array(DataTypes.INT(), DataTypes.BIGINT(), DataTypes.STRING())).build()
    util.tableEnv.registerTableSource("MyTable", new TestProjectableTableSource(
      true,
      tableSchema,
      new RowTypeInfo(
        tableSchema.getFieldDataTypes.map(TypeInfoDataTypeConverter.fromDataTypeToTypeInfo),
        tableSchema.getFieldNames),
      Seq.empty[Row])
    )
  }

  @Test
  def testSimpleProject(): Unit = {
    util.verifyPlan("SELECT a, c FROM MyTable")
  }

  @Test
  def testCannotProject(): Unit = {
    util.verifyPlan("SELECT a, c, b + 1 FROM MyTable")
  }

  @Test
  def testProjectWithUdf(): Unit = {
    util.verifyPlan("SELECT a, TRIM(c) FROM MyTable")
  }

  @Test
  def testProjectWithoutInputRef(): Unit = {
    util.verifyPlan("SELECT COUNT(1) FROM MyTable")
  }

  @Test
  def testNestedProject(): Unit = {
    val nested1 = new RowTypeInfo(
      Array(Types.STRING, Types.INT).asInstanceOf[Array[TypeInformation[_]]],
      Array("name", "value")
    )

    val nested2 = new RowTypeInfo(
      Array(Types.INT, Types.BOOLEAN).asInstanceOf[Array[TypeInformation[_]]],
      Array("num", "flag")
    )

    val deepNested = new RowTypeInfo(
      Array(nested1, nested2).asInstanceOf[Array[TypeInformation[_]]],
      Array("nested1", "nested2")
    )

    val tableSchema = new TableSchema(
      Array("id", "deepNested", "nested", "name"),
      Array(Types.INT, deepNested, nested1, Types.STRING))

    val returnType = new RowTypeInfo(
      Array(Types.INT, deepNested, nested1, Types.STRING).asInstanceOf[Array[TypeInformation[_]]],
      Array("id", "deepNested", "nested", "name"))

    util.tableEnv.registerTableSource(
      "T",
      new TestNestedProjectableTableSource(true, tableSchema, returnType, Seq()))

    val sqlQuery =
      """
        |SELECT id,
        |    deepNested.nested1.name AS nestedName,
        |    nested.`value` AS nestedValue,
        |    deepNested.nested2.flag AS nestedFlag,
        |    deepNested.nested2.num AS nestedNum
        |FROM T
      """.stripMargin
    util.verifyPlan(sqlQuery)
  }

}
