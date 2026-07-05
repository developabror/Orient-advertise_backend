package uz.orientadvertise.services.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(packages = "uz.orientadvertise.services", importOptions = {ImportOption.DoNotIncludeTests.class})
class LayerDependencyTest {

    @ArchTest
    static final ArchRule strict_layer_dependencies = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Api").definedBy("uz.orientadvertise.services.service..")
            .layer("Service").definedBy("uz.orientadvertise.services.service..")
            .layer("Infra").definedBy("uz.orientadvertise.services.infra..")
            .layer("Domain").definedBy("uz.orientadvertise.services.domain..")
            .layer("Common").definedBy("uz.orientadvertise.services.common..")
            .whereLayer("Api").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Api")
            .whereLayer("Infra").mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Api", "Service", "Infra")
            .whereLayer("Common").mayOnlyBeAccessedByLayers("Api", "Service", "Infra", "Domain");
}
