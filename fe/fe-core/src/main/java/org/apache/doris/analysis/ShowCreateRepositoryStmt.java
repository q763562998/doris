// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.analysis;

import org.apache.doris.catalog.Column;
import org.apache.doris.catalog.ScalarType;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.qe.ShowResultSetMetaData;

// SHOW CREATE REPOSITORY statement
public class ShowCreateRepositoryStmt extends ShowStmt {

    private static final ShowResultSetMetaData META_DATA =
            ShowResultSetMetaData.builder()
                            .addColumn(new Column("RepoName", ScalarType.createVarchar(128)))
                            .addColumn(new Column("CreateStmt", ScalarType.createVarchar(65535)))
                            .build();

    private final String repoName;

    public ShowCreateRepositoryStmt(String repoName) {
        this.repoName = repoName;
    }

    public String getRepoName() {
        return this.repoName;
    }

    @Override
    public void analyze(Analyzer analyzer) throws AnalysisException {

    }

    @Override
    public ShowResultSetMetaData getMetaData() {
        return META_DATA;
    }
}
