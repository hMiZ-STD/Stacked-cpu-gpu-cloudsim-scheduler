package org.cloudbus.cloudsim.cosched;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture rules 6 and 7, enforced by the build: policies see only the cluster view, the
 * round plan and the models, and never each other; the core names no concrete policy.
 */
class ArchitectureTest {

    static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.cloudbus.cloudsim.cosched");

    @Test
    void policiesDependOnlyOnCoreModelsAndPolicyInterfaces() {
        classes().that().resideInAPackage("..cosched.policy..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "..cosched.core..", "..cosched.model..", "..cosched.policy..", "java..")
                .check(CLASSES);
    }

    @Test
    void coreDependsOnNothingElseInTheModule() {
        noClasses().that().resideInAPackage("..cosched.core..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..cosched.model..", "..cosched.policy..", "..cosched.broker..", "..cosched.metrics..",
                        "..cosched.workload..", "..cosched.config..", "org.cloudbus.cloudsim.gpu..")
                .check(CLASSES);
    }

    @Test
    void modelsDoNotDependOnPoliciesOrTheBroker() {
        noClasses().that().resideInAPackage("..cosched.model..")
                .should().dependOnClassesThat().resideInAnyPackage("..cosched.policy..", "..cosched.broker..", "..cosched.metrics..")
                .check(CLASSES);
    }

    @Test
    void noPolicyImplementationUsesAnother() {
        classes().that().resideInAPackage("..cosched.policy.impl..")
                .should(new ArchCondition<JavaClass>("not depend on another policy implementation") {
                    @Override
                    public void check(JavaClass c, ConditionEvents events) {
                        String own = c.getName().split("\\$")[0];
                        for (Dependency d : c.getDirectDependenciesFromSelf()) {
                            JavaClass t = d.getTargetClass();
                            String target = t.getName().split("\\$")[0];
                            if (t.getPackageName().endsWith("cosched.policy.impl") && !target.equals(own))
                                events.add(SimpleConditionEvent.violated(d, d.getDescription()));
                        }
                    }
                })
                .check(CLASSES);
    }

    @Test
    void onlyTheStandardLibraryAndTestsNameConcretePolicies() {
        noClasses().that().resideOutsideOfPackage("..cosched.policy.impl..")
                .and().doNotHaveFullyQualifiedName(StandardPlugins.class.getName())
                .should().dependOnClassesThat().resideInAPackage("..cosched.policy.impl..")
                .check(CLASSES);
    }
}
