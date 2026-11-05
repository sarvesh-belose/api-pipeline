package com.crisil.apiorch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * US-1.1 AC10: every {@code *IT} carries no tag or {@code slow}, so a new tier cannot appear unnoticed and a slow
 * test cannot join the everyday build under another name.
 */
class TestTieringTest {

    private static final Set<String> ALLOWED_TAGS = Set.of("slow");

    private static final ArchCondition<JavaClass> HAVE_ONLY_ALLOWED_TAGS =
            new ArchCondition<>("carry no tag other than " + ALLOWED_TAGS) {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    Class<?> type = item.reflect();
                    Arrays.stream(type.getAnnotationsByType(Tag.class))
                            .map(Tag::value)
                            .filter(tag -> !ALLOWED_TAGS.contains(tag))
                            .forEach(tag -> events.add(SimpleConditionEvent.violated(item,
                                    item.getName() + " is tagged '" + tag + "'")));
                }
            };

    @Test
    void integrationTestsUseOnlyKnownTiers() {
        JavaClasses tests = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
                .importPackages("com.crisil.apiorch");
        classes().that().haveSimpleNameEndingWith("IT").and().areTopLevelClasses()
                .should(HAVE_ONLY_ALLOWED_TAGS)
                .check(tests);
    }
}
