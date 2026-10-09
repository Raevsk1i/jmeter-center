package com.ltplatform.generator.web;

import com.ltplatform.generator.dto.GeneratorDtos.CreateCredentialRequest;
import com.ltplatform.generator.dto.GeneratorDtos.CreateGeneratorRequest;
import com.ltplatform.generator.dto.GeneratorDtos.CredentialResponse;
import com.ltplatform.generator.dto.GeneratorDtos.GeneratorResponse;
import com.ltplatform.generator.dto.GeneratorDtos.ProvisionStepResponse;
import com.ltplatform.generator.dto.GeneratorDtos.UpdateGeneratorRequest;
import com.ltplatform.generator.service.GeneratorService;
import com.ltplatform.provisioning.service.ProvisioningService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class GeneratorController {
    private final GeneratorService generators;
    private final ProvisioningService provisioning;

    public GeneratorController(GeneratorService generators, ProvisioningService provisioning) {
        this.generators = generators;
        this.provisioning = provisioning;
    }

    @GetMapping("/generators")
    public List<GeneratorResponse> list() {
        return generators.list();
    }

    @GetMapping("/generators/{id}")
    public GeneratorResponse get(@PathVariable UUID id) {
        return generators.get(id);
    }

    @PostMapping("/generators")
    public GeneratorResponse create(@Valid @RequestBody CreateGeneratorRequest req, Authentication auth) {
        return generators.create(req, auth.getName());
    }

    @PutMapping("/generators/{id}")
    public GeneratorResponse update(@PathVariable UUID id, @RequestBody UpdateGeneratorRequest req, Authentication auth) {
        return generators.update(id, req, auth.getName());
    }

    @DeleteMapping("/generators/{id}")
    public Map<String, String> delete(@PathVariable UUID id, Authentication auth) {
        generators.delete(id, auth.getName());
        return Map.of("status", "deleted");
    }

    @PostMapping("/generators/{id}/provision")
    public Map<String, String> provision(@PathVariable UUID id, Authentication auth) {
        generators.reprovision(id, auth.getName());
        return Map.of("status", "provisioning");
    }

    @GetMapping("/generators/{id}/provision/steps")
    public List<ProvisionStepResponse> steps(@PathVariable UUID id) {
        return provisioning.listSteps(id);
    }

    @PostMapping("/generators/{id}/check")
    public Map<String, Object> check(@PathVariable UUID id) {
        return provisioning.checkConnectivity(id);
    }

    @PostMapping("/ssh-credentials")
    public CredentialResponse createCred(@Valid @RequestBody CreateCredentialRequest req, Authentication auth) {
        return generators.createCredential(req, auth.getName());
    }

    @GetMapping("/ssh-credentials")
    public List<CredentialResponse> listCreds() {
        return generators.listCredentials();
    }
}
