package probe;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** README §13 architecture rule, as a smoke test that ArchUnit 1.5 reads this JDK's class files. */
@AnalyzeClasses(packages = "probe", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchRulesTest {

    @ArchTest
    static final ArchRule engineIsPure = noClasses().that().resideInAPackage("..engine..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web.client..", "org.springframework.jdbc..", "java.net.http..");
}
