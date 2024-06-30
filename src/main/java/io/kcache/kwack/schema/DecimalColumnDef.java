/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kcache.kwack.schema;

import org.duckdb.DuckDBColumnType;

public class DecimalColumnDef extends ColumnDef {
    private final int precision;
    private final int scale;

    public DecimalColumnDef(int precision, int scale) {
        this(ColumnStrategy.NOT_NULL_STRATEGY, precision, scale);
    }

    public DecimalColumnDef(ColumnStrategy columnStrategy, int precision, int scale) {
        super(DuckDBColumnType.DECIMAL, columnStrategy);
        this.precision = precision;
        this.scale = scale;
    }

    public int getPrecision() {
        return precision;
    }

    public int getScale() {
        return scale;
    }

    @Override
    public String toDdl() {
        String ddl = columnType.name() + "(" + precision + ", " + scale + ")";
        if (columnStrategy != null) {
            // TODO fix default
            return ddl + " " + columnStrategy.toDdl();
        } else {
            return ddl;
        }
    }
}
