package uz.orientadvertise.services.infra;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "uz.orientadvertise.services")
@EntityScan(basePackages = {"uz.orientadvertise.services.domain", "uz.orientadvertise.services.infra"})
@EnableJpaRepositories(basePackages = {"uz.orientadvertise.services.domain.repository", "uz.orientadvertise.services.infra"})
public class TestApplication {
}
