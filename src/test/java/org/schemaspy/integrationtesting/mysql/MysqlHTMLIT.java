/*
 * Copyright (C) 2018 Nils Petzaell
 *
 * This file is part of SchemaSpy.
 *
 * SchemaSpy is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SchemaSpy is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with SchemaSpy. If not, see <http://www.gnu.org/licenses/>.
 */
package org.schemaspy.integrationtesting.mysql;

import com.github.npetzall.testcontainers.junit.jdbc.JdbcContainerRule;
import org.assertj.core.api.SoftAssertions;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schemaspy.cli.SchemaSpyRunner;
import org.schemaspy.integrationtesting.MysqlSuite;
import org.schemaspy.testing.IgnoreUsingXPath;
import org.schemaspy.testing.SuiteOrTestJdbcContainerRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringRunner;
import org.testcontainers.containers.MySQLContainer;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;
import org.xmlunit.diff.DifferenceEvaluators;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.github.npetzall.testcontainers.junit.jdbc.JdbcAssumptions.assumeDriverIsPresent;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Nils Petzaell
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@DirtiesContext
public class MysqlHTMLIT {

    private static URL expectedXML = MysqlHTMLIT.class.getResource("/integrationTesting/mysql/expecting/mysqlhtml/htmlit.htmlit.xml");
    private static URL expectedDeletionOrder = MysqlHTMLIT.class.getResource("/integrationTesting/mysql/expecting/mysqlhtml/deletionOrder.txt");
    private static URL expectedInsertionOrder = MysqlHTMLIT.class.getResource("/integrationTesting/mysql/expecting/mysqlhtml/insertionOrder.txt");

    @ClassRule
    public static JdbcContainerRule<MySQLContainer> jdbcContainerRule =
            new SuiteOrTestJdbcContainerRule<>(
                    MysqlSuite.jdbcContainerRule,
                    new JdbcContainerRule<MySQLContainer>(() -> new MySQLContainer<>("mysql:5"))
                        .assumeDockerIsPresent().withAssumptions(assumeDriverIsPresent())
                        .withQueryString("?useSSL=false")
                        .withInitScript("integrationTesting/mysql/dbScripts/htmlit.sql")
                        .withInitUser("root", "test")
            );

    @Autowired
    private SchemaSpyRunner schemaSpyRunner;

    private static final AtomicBoolean shouldRun = new AtomicBoolean(true);

    @Before
    public synchronized void generateHTML() throws Exception {
        if (shouldRun.get()) {
            String[] args = new String[]{
                    "-t", "mysql",
                    "-db", "htmlit",
                    "-s", "htmlit",
                    "-host", jdbcContainerRule.getContainer().getContainerIpAddress() + ":" + String.valueOf(jdbcContainerRule.getContainer().getMappedPort(3306)),
                    "-port", String.valueOf(jdbcContainerRule.getContainer().getMappedPort(3306)),
                    "-u", jdbcContainerRule.getContainer().getUsername(),
                    "-p", jdbcContainerRule.getContainer().getPassword(),
                    "-o", "target/mysqlhtml",
                    "-connprops", "useSSL\\=false"
            };
            schemaSpyRunner.run(args);
            shouldRun.set(false);
        }
    }

    @Test
    public void verifyXML() {
        Diff d = DiffBuilder.compare(Input.fromURL(expectedXML))
                .withTest(Input.fromFile("target/mysqlhtml/htmlit.htmlit.xml"))
                .withDifferenceEvaluator(DifferenceEvaluators.chain(DifferenceEvaluators.Default, new IgnoreUsingXPath("/database[1]/@type")))
                .build();
        assertThat(d.getDifferences()).isEmpty();
    }

    @Test
    public void verifyDeletionOrder() throws IOException {
        assertThat(Files.newInputStream(Paths.get("target/mysqlhtml/deletionOrder.txt"), StandardOpenOption.READ)).hasSameContentAs(expectedDeletionOrder.openStream());
    }

    @Test
    public void verifyInsertionOrder() throws IOException {
        assertThat(Files.newInputStream(Paths.get("target/mysqlhtml/insertionOrder.txt"), StandardOpenOption.READ)).hasSameContentAs(expectedInsertionOrder.openStream());
    }

    @Test
    public void producesSameContet() throws IOException {
        String target = "target/mysqlhtml";
        Path expectedPath = Paths.get("src/test/resources/integrationTesting/mysql/expecting/mysqlhtml");
        List<Path> expectations;
        try (Stream<Path> pathStream = Files.find(expectedPath, 5, (p, a) -> a.isRegularFile())) {
            expectations = pathStream.filter(p -> {
                String fileName = p.getFileName().toString().toLowerCase();
                return fileName.endsWith("html") || fileName.endsWith("dot");
            }).collect(Collectors.toList());
        }
        assertThat(expectations.size()).isGreaterThan(0);
        SoftAssertions softAssertions = new SoftAssertions();
        for (Path expect : expectations) {
            List<String> expectLines = Files.readAllLines(expect, StandardCharsets.UTF_8);
            Path actual = Paths.get(target, expectedPath.relativize(expect).toString());
            List<String> actualLines = Files.readAllLines(actual, StandardCharsets.UTF_8);
            softAssertions.assertThat(actualLines).as("%s doesn't have the expected number of lines: %s", actual.toString(), expectLines.size()).hasSameSizeAs(expectLines);
            softAssertions.assertThat(actualLines).usingElementComparator((a, e) -> {
                String trimmed = e.trim();
                if (trimmed.startsWith("<strong>Generated by")) {
                    return 0;
                } else if (trimmed.startsWith("<area shape=")) {
                    return 0;
                } else if (trimmed.startsWith("<p>Generated on")) {
                    return 0;
                } else if (trimmed.startsWith("<p>Database Type")) {
                    return 0;
                } else if (trimmed.startsWith("// dot")) {
                    return 0;
                } else if (trimmed.startsWith("// SchemaSpy rev")) {
                    return 0;
                }
                return a.compareTo(e);
            }).as("%s isn't as expected", actual.toString()).containsAll(expectLines);
        }
        softAssertions.assertAll();
    }
}
