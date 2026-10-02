package com.lambda.fusion.form.design.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.Set;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

class FormChangelogTest {
    @Test
    void authorityRoutePermissionAndManagementLockMigrationsExecute() throws Exception {
        try (var connection =
                        DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
                var resources = new ClassLoaderResourceAccessor();
                var liquibase = new Liquibase(
                        "META-INF/db/changelogs/lambda-authority-changelog.xml",
                        resources,
                        new JdbcConnection(connection))) {
            var selected = Set.of(
                    "lambda-authority-2026031200051", "lambda-authority-202610010001", "lambda-authority-202610010002");
            liquibase.getDatabaseChangeLog().getChangeSets().removeIf(change -> !selected.contains(change.getId()));
            assertThat(liquibase.getDatabaseChangeLog().getChangeSets()).hasSize(3);
            liquibase.update(new Contexts(), new LabelExpression());
            try (var statement = connection.createStatement();
                    var row = statement.executeQuery("SELECT ID FROM la_resource_tree_lock")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("tree");
            }
            try (var statement = connection.createStatement();
                    var row = statement.executeQuery("SELECT RES_PATH FROM la_resources WHERE ID='FmDsgR001'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("form:design:route");
            }
            liquibase.update(new Contexts(), new LabelExpression());
            assertThat(liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression()))
                    .isEmpty();
        }
    }

    @Test
    void formChangelogCreatesLayoutAndLabelColumnsAndCanRunAgain() throws Exception {
        try (var connection =
                        DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
                var resources = new ClassLoaderResourceAccessor();
                var liquibase = new Liquibase(
                        "META-INF/db/changelogs/lambda-form-changelog.xml",
                        resources,
                        new JdbcConnection(connection))) {
            liquibase.update(new Contexts(), new LabelExpression());
            try (var columns = connection.getMetaData().getColumns(null, null, "LA_FORM_DEF", "FORM_LAYOUT")) {
                assertThat(columns.next()).isTrue();
            }
            try (var columns = connection.getMetaData().getColumns(null, null, "LA_FORM_FIELD", "FIELD_LABEL")) {
                assertThat(columns.next()).isTrue();
                assertThat(columns.getInt("COLUMN_SIZE")).isEqualTo(128);
            }
            try (var columns = connection.getMetaData().getColumns(null, null, "LA_FORM_DEF", "ROUTE_RESOURCE_ID")) {
                assertThat(columns.next()).isTrue();
            }
            liquibase.update(new Contexts(), new LabelExpression());
            assertThat(liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression()))
                    .isEmpty();
        }
    }
}
