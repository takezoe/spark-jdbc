package com.zensolution.jdbc.spark.internal;

import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.catalyst.analysis.UnresolvedRelation;
import org.apache.spark.sql.catalyst.parser.ParseException;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.execution.datasources.jdbc.JdbcUtils;
import org.apache.spark.sql.jdbc.JdbcType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public class SparkService {
    protected SparkSession buildSparkSession(ConnectionInfo info) {
        final SparkSession.Builder builder = SparkSession.builder().master("local").appName("parquet-jdbc-driver")
                .config("spark.sql.session.timeZone", "UTC");
        getOptions(info.getProperties(), "spark").entrySet().stream()
                .filter(entry->entry.getKey().startsWith("spark."))
                .forEach(entry-> builder.config(entry.getKey(), entry.getValue()));
        return builder.getOrCreate();
    }

    public Dataset<Row> executeQuery(ConnectionInfo info, String sqlText) throws ParseException {
        SparkSession spark = buildSparkSession(info);
        prepareTempView(info, sqlText);
        return spark.sql(sqlText);
    }

    public void prepareTempView(ConnectionInfo info, String sqlText) throws ParseException {
        Map<String, String> options = getOptions(info.getProperties(), info.getFormat().name());

        SparkSession spark = buildSparkSession(info);
        Set<String> tables = getRelations(spark.sessionState().sqlParser().parsePlan(sqlText));
        tables.forEach(table -> {
            SupportedFormat format = info.getFormat();
            Dataset<Row> ds = spark.read().format(format.name().toLowerCase(Locale.getDefault()))
                    .options(options)
                    .load(format.getSparkPath(info.getPath(), table));
            ds.createOrReplaceTempView(table);
        });
    }

    private Map<String, String> getOptions(Properties info, String prefix) {
        return info.entrySet().stream()
                .filter(e->e.getKey().toString().toLowerCase(Locale.getDefault())
                        .startsWith(prefix.toLowerCase(Locale.getDefault())+"."))
                .collect(
                        Collectors.toMap(
                                e -> e.getKey().toString().substring(prefix.length()+1),
                                e -> e.getValue().toString()
                        )
                );
    }

    private Set<String> getRelations(LogicalPlan plan) {
        return scala.collection.JavaConverters.seqAsJavaListConverter(plan.collectLeaves()).asJava()
                .stream()
                .map(logicalPlan -> {
                    if (logicalPlan instanceof UnresolvedRelation) {
                        return ((UnresolvedRelation) logicalPlan).tableName();
                    }
                    return "";
                }).collect(Collectors.toSet());
    }

    public Dataset<Row> getTables(ConnectionInfo info) {
        List<Row> tables = Arrays.stream(buildSparkSession(info).sqlContext().tableNames())
                .map(table->RowFactory.create(table, "", "", ""))
                .collect(Collectors.toList());

        List<StructField> listOfStructField = new ArrayList<>();
        listOfStructField.add(DataTypes.createStructField("TABLE_NAME", DataTypes.StringType, true));
        listOfStructField.add(DataTypes.createStructField("TABLE_TYPE", DataTypes.StringType, true));
        listOfStructField.add(DataTypes.createStructField("TABLE_SCHEM", DataTypes.StringType, true));
        listOfStructField.add(DataTypes.createStructField("TABLE_CAT", DataTypes.StringType, true));

        StructType structType=DataTypes.createStructType(listOfStructField);
        return buildSparkSession(info).createDataFrame(tables, structType);
    }

    public Dataset<Row> getColumns(ConnectionInfo info, String table) {
        StructField[] fields = buildSparkSession(info).sqlContext().table(table).schema().fields();
        List<Row> columns = new ArrayList<>();
        for (int i=0; i<fields.length; i++) {
            JdbcType jdbcType = JdbcUtils.getCommonJDBCType(fields[i].dataType()).get();
            columns.add(RowFactory.create("", "", table, fields[i].name(), jdbcType.jdbcNullType(),
                    jdbcType.databaseTypeDefinition()));
        }
        List<StructField> listOfStructField = new ArrayList<>();
        listOfStructField.add(DataTypes.createStructField("TABLE_CAT", DataTypes.StringType, true));
        listOfStructField.add(DataTypes.createStructField("TABLE_SCHEM", DataTypes.StringType, true));
        listOfStructField.add(DataTypes.createStructField("TABLE_NAME", DataTypes.StringType, true));
        listOfStructField.add(DataTypes.createStructField("COLUMN_NAME", DataTypes.StringType, true));
        listOfStructField.add(DataTypes.createStructField("DATA_TYPE", DataTypes.IntegerType, true));
        listOfStructField.add(DataTypes.createStructField("TYPE_NAME", DataTypes.StringType, true));

        StructType structType=DataTypes.createStructType(listOfStructField);
        return buildSparkSession(info).createDataFrame(columns, structType);
    }
}
