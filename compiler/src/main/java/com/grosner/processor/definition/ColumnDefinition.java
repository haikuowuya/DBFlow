package com.grosner.processor.definition;

import com.google.common.collect.Sets;
import com.grosner.dbflow.annotation.Column;
import com.grosner.dbflow.annotation.ForeignKeyReference;
import com.grosner.dbflow.sql.SQLiteType;
import com.grosner.processor.Classes;
import com.grosner.processor.ProcessorUtils;
import com.grosner.processor.model.ProcessorManager;
import com.grosner.processor.model.builder.AdapterQueryBuilder;
import com.grosner.processor.model.builder.MockConditionQueryBuilder;
import com.grosner.processor.utils.ModelUtils;
import com.grosner.processor.writer.FlowWriter;
import com.squareup.javawriter.JavaWriter;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.tools.Diagnostic;

import java.io.IOException;

/**
 * Author: andrewgrosner
 * Description:
 */
public class ColumnDefinition extends BaseDefinition implements FlowWriter {

    public String columnName;

    public String columnFieldName;

    public String columnFieldType;

    private String modelContainerType;

    public TypeElement modelType;

    public boolean hasTypeConverter = false;

    public int columnType;

    public Column column;

    public ForeignKeyReference[] foreignKeyReferences;

    public boolean isModel;

    /**
     * Whether this field is itself a model container
     */
    public boolean isModelContainer;

    public ColumnDefinition(ProcessorManager processorManager, VariableElement element) {
        super(element, processorManager);

        column = element.getAnnotation(Column.class);
        this.columnName = column.name().equals("") ? element.getSimpleName().toString() : column.name();
        this.columnFieldName = element.getSimpleName().toString();
        this.columnFieldType = element.asType().toString();

        if (element.asType().getKind().isPrimitive()) {
            this.modelType = processorManager.getTypeUtils().boxedClass((PrimitiveType) element.asType());
        } else {
            boolean isAModelContainer = false;
            DeclaredType declaredType = null;
            if (element.asType() instanceof DeclaredType) {
                declaredType = (DeclaredType) element.asType();
                isAModelContainer = !declaredType.getTypeArguments().isEmpty();
            } else if (element.asType() instanceof ArrayType) {
                processorManager.getMessager().printMessage(Diagnostic.Kind.ERROR, "Columns cannot be of array type.");
            }

            // TODO: not currently correctly supporting model containers as fields. Certainly is possible
            if (isAModelContainer) {
                isModelContainer = true;
                // TODO: hack for now
                modelContainerType = columnFieldType;
                this.modelType = (TypeElement) processorManager.getTypeUtils().asElement(declaredType.getTypeArguments().get(0));
                columnFieldType = modelType.asType().toString();
            } else {
                this.modelType = processorManager.getElements().getTypeElement(element.asType().toString());
            }
        }

        columnType = column.columnType();

        if (columnType == Column.FOREIGN_KEY) {
            foreignKeyReferences = column.references();
        }

        isModel = ProcessorUtils.implementsClass(processorManager.getProcessingEnvironment(), Classes.MODEL, modelType);

        // Any annotated members, otherwise we will use the scanner to find other ones
        final TypeConverterDefinition typeConverterDefinition = processorManager.getTypeConverterDefinition(modelType);
        if (typeConverterDefinition != null) {
            hasTypeConverter = true;
        }

        // If type cannot be represented, we will get Type converter anyways
        if (!hasTypeConverter && !isModel) {
            hasTypeConverter = !SQLiteType.containsClass(columnFieldType);
        }

    }


    @Override
    public void write(JavaWriter javaWriter) throws IOException {
        if (isModel || isModelContainer) {
            for (ForeignKeyReference reference : foreignKeyReferences) {
                writeColumnDefinition(javaWriter, (columnName + "_" + reference.columnName()).toUpperCase(), reference.columnName());
            }
        } else {
            writeColumnDefinition(javaWriter, columnName);
        }
    }

    protected void writeColumnDefinition(JavaWriter javaWriter, String columnName) throws IOException {
        writeColumnDefinition(javaWriter, columnName.toUpperCase(), columnName);
    }

    /**
     * When the field name is different from the column name (foreign key names)
     *
     * @param javaWriter The writer
     * @param fieldName  The name of the filed
     * @param columnName The column name
     * @throws IOException if write fails
     */
    protected void writeColumnDefinition(JavaWriter javaWriter, String fieldName, String columnName) throws IOException {
        javaWriter.emitField("String", fieldName,
                Sets.newHashSet(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL),
                "\"" + columnName + "\"");
        javaWriter.emitEmptyLine();
    }

    public void writeSaveDefinition(JavaWriter javaWriter, boolean isModelContainerDefinition) throws IOException {
        if (columnType == Column.FOREIGN_KEY && isModel) {
            javaWriter.emitEmptyLine();
            if (isModelContainer) {
                javaWriter.emitSingleLineComment("Begin Saving Model Container To DB");
            } else {
                javaWriter.emitSingleLineComment("Begin Saving Foreign Key References From Model");
            }

            if (isModelContainerDefinition) {
                String modelContainerName = ModelUtils.getVariable(true) + columnFieldName;
                javaWriter.emitStatement("ModelContainer %1s = %1s.getInstance(%1s.getValue(\"%1s\"), %1s.class)", modelContainerName,
                        ModelUtils.getVariable(true), ModelUtils.getVariable(true), columnFieldName, columnFieldType);
                javaWriter.emitStatement("%1s.save(false)", modelContainerName);
                for (ForeignKeyReference foreignKeyReference : foreignKeyReferences) {
                    AdapterQueryBuilder adapterQueryBuilder = new AdapterQueryBuilder();
                    adapterQueryBuilder.appendContentValues()
                            .appendPut(foreignKeyReference.columnName())
                                .appendCast(ModelUtils.getClassFromAnnotation(foreignKeyReference))
                                    .append(modelContainerName)
                                    .append(".")
                                    .appendGetValue(foreignKeyReference.foreignColumnName())
                            .append("))");
                    javaWriter.emitStatement(adapterQueryBuilder.getQuery());
                }

            } else {
                String modelStatement = ModelUtils.getModelStatement(columnFieldName);
                javaWriter.beginControlFlow("if (%1s != null)", modelStatement);
                javaWriter.emitStatement("%1s.save(false)", modelStatement);
                for (ForeignKeyReference foreignKeyReference : foreignKeyReferences) {
                    ModelUtils.writeContentValueStatement(javaWriter, foreignKeyReference.columnName(),
                            columnName, ModelUtils.getClassFromAnnotation(foreignKeyReference),
                            foreignKeyReference.foreignColumnName(),
                            false, isModelContainer, true, false, columnFieldType);
                }
                javaWriter.endControlFlow();
            }
            javaWriter.emitSingleLineComment("End");
            javaWriter.emitEmptyLine();
        } else {
            // Normal field
            String newFieldType = null;

            // convert field type for what type converter reports
            if (hasTypeConverter) {
                TypeConverterDefinition typeConverterDefinition = manager.getTypeConverterDefinition(modelType);
                if (typeConverterDefinition == null) {
                    manager.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format("No Type Converter found for %1s", modelType));
                } else {
                    newFieldType = typeConverterDefinition.getDbElement().asType().toString();
                }
            } else {
                newFieldType = columnFieldType;
            }

            String getType = columnFieldType;
            // Type converters can never be primitive except boolean
            if (element.asType().getKind().isPrimitive()) {
                getType = manager.getTypeUtils().boxedClass((PrimitiveType) element.asType()).asType().toString();
            }

            if(isModelContainerDefinition) {
                if (element.asType().getKind().isPrimitive()) {
                    newFieldType = manager.getTypeUtils().boxedClass((PrimitiveType) element.asType()).asType().toString();
                }
            }

            ModelUtils.writeContentValueStatement(javaWriter, columnName, columnName,
                    newFieldType, columnFieldName, isModelContainerDefinition, isModelContainer, false, hasTypeConverter, getType);
        }
    }

    public void writeLoadFromCursorDefinition(JavaWriter javaWriter, boolean isModelContainerDefinition) throws IOException {
        if (columnType == Column.FOREIGN_KEY) {
            //TODO: This is wrong, should be using condition query builder
            javaWriter.emitEmptyLine();
            javaWriter.emitSingleLineComment("Begin Loading %1s Model Foreign Key", columnFieldName);

            // special case for model objects within class
            if (!isModelContainer && !isModelContainerDefinition && isModel) {

                ModelUtils.writeColumnIndexCheckers(javaWriter, foreignKeyReferences);
                MockConditionQueryBuilder conditionQueryBuilder = new MockConditionQueryBuilder()
                        .appendForeignKeyReferences(columnFieldType + TableDefinition.DBFLOW_TABLE_TAG, columnName, foreignKeyReferences);

                String rawConditionStatement = String.format("new Select().from(%1s).where().%1s.querySingle()",
                        ModelUtils.getFieldClass(columnFieldType), conditionQueryBuilder);

                AdapterQueryBuilder adapterQueryBuilder = new AdapterQueryBuilder().appendVariable(false);
                adapterQueryBuilder.append(".").append(columnFieldName).appendSpaceSeparated("=");
                adapterQueryBuilder.append(rawConditionStatement);
                javaWriter.emitStatement(adapterQueryBuilder.getQuery());

                javaWriter.endControlFlow();
            } else {
                if (isModelContainerDefinition) {
                    String modelContainerName = ModelUtils.getVariable(true) + columnFieldName;
                    javaWriter.emitStatement("ModelContainer %1s = %1s.getInstance(%1s.newDataInstance(), %1s.class)",
                            modelContainerName, ModelUtils.getVariable(true), ModelUtils.getVariable(true),
                            columnFieldType);
                    for (ForeignKeyReference foreignKeyReference : foreignKeyReferences) {

                        ModelUtils.writeColumnIndexCheckers(javaWriter, foreignKeyReferences);
                        AdapterQueryBuilder adapterQueryBuilder = new AdapterQueryBuilder();
                        adapterQueryBuilder.append(modelContainerName)
                                .appendPut(foreignKeyReference.foreignColumnName())
                                .append(ModelUtils.getCursorStatement(ModelUtils.getClassFromAnnotation(foreignKeyReference),
                                        foreignKeyReference.columnName()))
                                .append(")");
                        javaWriter.emitStatement(adapterQueryBuilder.getQuery());

                        javaWriter.endControlFlow();
                    }

                    javaWriter.emitStatement("%1s.put(\"%1s\",%1s.getData())", ModelUtils.getVariable(true), columnFieldName, modelContainerName);
                } else {

                    for (ForeignKeyReference foreignKeyReference : foreignKeyReferences) {
                        // instantiate model container
                        if(isModelContainer) {
                            AdapterQueryBuilder containerBuilder =
                                    new AdapterQueryBuilder().appendVariable(isModelContainerDefinition)
                                            .append(".").append(columnFieldName)
                                            .appendSpaceSeparated("=")
                                            .append("new ").append(element.asType().toString())
                                            .appendParenthesisEnclosed(ModelUtils.getFieldClass(columnFieldType));
                            javaWriter.emitStatement(containerBuilder.getQuery());
                        }

                        ModelUtils.writeLoadFromCursorDefinitionField(javaWriter, manager, ModelUtils.getClassFromAnnotation(foreignKeyReference),
                                columnFieldName, foreignKeyReference.columnName(), foreignKeyReference.foreignColumnName(), element, false, isModelContainerDefinition, isModelContainer);
                    }
                }
            }
            javaWriter.emitSingleLineComment("End");
            javaWriter.emitEmptyLine();

        } else {
            String getType = columnFieldType;
            // Type converters can never be primitive except boolean
            if (element.asType().getKind().isPrimitive()) {
                getType = manager.getTypeUtils().boxedClass((PrimitiveType) element.asType()).asType().toString();
            }

            ModelUtils.writeLoadFromCursorDefinitionField(javaWriter, manager, getType, columnFieldName,
                    columnName, "", modelType, hasTypeConverter, isModelContainerDefinition, this.isModelContainer);
        }
    }

    public void writeToModelDefinition(JavaWriter javaWriter) throws IOException {
        AdapterQueryBuilder queryBuilder = new AdapterQueryBuilder();
        queryBuilder.appendVariable(false).append(".").append(columnFieldName);
        queryBuilder.appendSpaceSeparated("=");

        if (hasTypeConverter) {
            queryBuilder.appendTypeConverter(columnFieldType, columnFieldType, true);
        } else {
            queryBuilder.appendCast(isModelContainer ? modelContainerType : columnFieldType);
        }

        if (isModel) {
            queryBuilder.appendVariable(true)
                    .append(".getInstance(");
        }

        queryBuilder.appendVariable(true).append(".").appendGetValue(columnFieldName);

        if (!isModel) {
            queryBuilder.append(")");
        }

        if (isModel) {
            queryBuilder.append(",").append(ModelUtils.getFieldClass(columnFieldType)).append(")");
        }

        if (hasTypeConverter) {
            queryBuilder.append(")");
        }

        if (isModel) {
            queryBuilder.append(".toModel())");
        }

        javaWriter.emitStatement(queryBuilder.getQuery());
    }


}
