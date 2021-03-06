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

package org.apache.spark.sql.execution.datasources.oap.index

import org.scalatest.BeforeAndAfterEach

import org.apache.spark.SparkConf
import org.apache.spark.sql.{QueryTest, Row}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.util.Utils


/**
 * Index suite for BitMap Index
 */
class BitMapIndexSuite extends QueryTest with SharedSQLContext with BeforeAndAfterEach {
  import testImplicits._

  sparkConf.set("spark.memory.offHeap.size", "100m")

  override def beforeEach(): Unit = {
    sqlContext.conf.setConf(SQLConf.OAP_IS_TESTING, true)
    val path = Utils.createTempDir().getAbsolutePath
    sql(s"""CREATE TEMPORARY VIEW oap_test (a INT, b STRING)
            | USING oap
            | OPTIONS (path '$path')""".stripMargin)
    sql(s"""CREATE TEMPORARY VIEW oap_test_date (a INT, b DATE)
            | USING oap
            | OPTIONS (path '$path')""".stripMargin)
    sql(s"""CREATE TEMPORARY VIEW parquet_test (a INT, b STRING)
            | USING parquet
            | OPTIONS (path '$path')""".stripMargin)
    sql(s"""CREATE TEMPORARY VIEW parquet_test_date (a INT, b DATE)
            | USING parquet
            | OPTIONS (path '$path')""".stripMargin)
    sql(s"""CREATE TABLE t_refresh (a int, b int)
            | USING oap
            | PARTITIONED by (b)""".stripMargin)
    sql(s"""CREATE TABLE t_refresh_parquet (a int, b int)
            | USING parquet
            | PARTITIONED by (b)""".stripMargin)
  }

  override def afterEach(): Unit = {
    sqlContext.dropTempTable("oap_test")
    sqlContext.dropTempTable("oap_test_date")
    sqlContext.dropTempTable("parquet_test")
    sqlContext.dropTempTable("parquet_test_date")
    sql("DROP TABLE IF EXISTS t_refresh")
    sql("DROP TABLE IF EXISTS t_refresh_parquet")
  }

  test("filtering without index") { // not passed for RangeInterval must be built with an index
    val data: Seq[(Int, String)] = (1 to 300).map { i => (i, s"this is test $i") }
    data.toDF("key", "value").createOrReplaceTempView("t")
    sql("insert overwrite table oap_test select * from t")

    checkAnswer(sql("SELECT * FROM oap_test WHERE a = 1"),
      Row(1, "this is test 1") :: Nil)

    checkAnswer(sql("SELECT * FROM oap_test WHERE a > 1 AND a <= 3"),
      Row(2, "this is test 2") :: Row(3, "this is test 3") :: Nil)
  }

  test("Single BitMap index single equal value test") {
    val data: Seq[(Int, String)] = (1 to 300).map { i => (i, s"this is test $i") }
    data.toDF("key", "value").createOrReplaceTempView("t")
    // sql("select * from t").show(500, false)
    sql("insert overwrite table oap_test select * from t")
    sql("create oindex index_bf on oap_test (a) USING BITMAP")
    checkAnswer(sql("SELECT * FROM oap_test WHERE a = 10"),
      Row(10, "this is test 10") :: Nil)
    checkAnswer(sql("SELECT * FROM oap_test WHERE a = 20"),
      Row(20, "this is test 20") :: Nil)
    checkAnswer(sql("SELECT * FROM oap_test WHERE a = 100"),
      Row(100, "this is test 100") :: Nil)
    assert(sql(s"SELECT * FROM oap_test WHERE a = 301").count() == 0)
    assert(sql(s"SELECT * FROM oap_test WHERE a = 310").count() == 0)
    assert(sql(s"SELECT * FROM oap_test WHERE a = 10301").count() == 0)
    assert(sql(s"SELECT * FROM oap_test WHERE a = 801").count() == 0)
    sql("drop oindex index_bf on oap_test")
  }

  test("Single BitMap index multiple equal value test") {
    val data: Seq[(Int, String)] = (1 to 300).map { i => (i, s"this is test $i") }
    data.toDF("key", "value").createOrReplaceTempView("t")
    sql("insert overwrite table oap_test select * from t")
    sql("create oindex index_bf on oap_test (a) USING BITMAP")
    assert(sql(s"SELECT * FROM oap_test WHERE a = 10 AND a = 11").count() == 0)
    checkAnswer(sql(s"SELECT * FROM oap_test WHERE a = 20 OR a = 21"),
      Row(20, "this is test 20") :: Row(21, "this is test 21") :: Nil)
    assert(sql(s"SELECT * FROM oap_test WHERE a = 10 AND a = 11").count() == 0)
    sql("drop oindex index_bf on oap_test")
  }

  test("BitMap index for range predicate which over multi partitions") {
    val data: Seq[(Int, String)] = (1 to 200).map { i => (i, s"this is test $i") }
    data.toDF("key", "value").createOrReplaceTempView("t")
    sql("insert overwrite table oap_test select * from t")
    sql("create oindex index_bf on oap_test (a) USING BITMAP")
    val result: Seq[(Int, String)] = (46 to 150).map { i => (i, s"this is test $i") }

    checkAnswer(sql("SELECT * FROM oap_test WHERE a >= 3 AND a <= 150 AND a > 45"),
      result.toDF("key", "value"))
    sql("drop oindex index_bf on oap_test")
  }

  test("BitMap index for or(like,like) bug") {
    val data: Seq[(Int, String)] = (1 to 200).map { i => (i, s"this is test $i") }
    data.toDF("key", "value").createOrReplaceTempView("t")
    sql("insert overwrite table oap_test select * from t")
    sql("create oindex index_bf on oap_test (a) USING BITMAP")
    val result: Seq[(Int, String)] = (180 to 199).map { i => (i, s"this is test $i") }
    val emptyResult : Seq[(Int, String)] = Seq.empty

    checkAnswer(sql("SELECT * FROM oap_test WHERE a >= 180 and a < 200 " +
          "AND (b like '%22%' or b like '%21%')"),
          emptyResult.toDF("key", "value"))

    checkAnswer(sql("SELECT * FROM oap_test WHERE a >= 180 and a < 200 " +
      "AND (b like '%18%' or b like '%19%')"),
      result.toDF("key", "value"))
    sql("drop oindex index_bf on oap_test")
  }
}
