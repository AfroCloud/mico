package io.github.ust.mico.core.REST;

import static org.springframework.hateoas.mvc.ControllerLinkBuilder.linkTo;
import static org.springframework.hateoas.mvc.ControllerLinkBuilder.methodOn;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.ust.mico.core.ClusterAwarenessFabric8;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.hateoas.Resource;
import org.springframework.hateoas.Resources;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.ust.mico.core.model.MicoApplication;
import io.github.ust.mico.core.persistence.MicoApplicationRepository;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(value = "/applications", produces = MediaTypes.HAL_JSON_VALUE)
public class ApplicationController {

    private static final String PATH_VARIABLE_SHORT_NAME = "shortName";
    private static final String PATH_VARIABLE_VERSION = "version";
    /**
     * Used by deployments
     */
    private static final String LABLE_APP_KEY = "app";

    /**
     * Used by services (MicoInterfaces)
     */
    private static final String LABLE_RUN_KEY = "run";

    private static final int MINIMAL_EXTERNAL_MICO_INTERFACE_COUNT = 1;

    @Autowired
    private MicoApplicationRepository applicationRepository;

    @GetMapping()
    public ResponseEntity<Resources<Resource<MicoApplication>>> getAllApplications() {
        List<MicoApplication> allApplications = applicationRepository.findAll();
        List<Resource<MicoApplication>> applicationResources = getApplicationResourceList(allApplications);

        return ResponseEntity.ok(
                new Resources<>(applicationResources,
                        linkTo(methodOn(ApplicationController.class).getAllApplications()).withSelfRel()));
    }

    @GetMapping("/{" + PATH_VARIABLE_SHORT_NAME + "}/{" + PATH_VARIABLE_VERSION + "}")
    public ResponseEntity<Resource<MicoApplication>> getApplicationByShortNameAndVersion(@PathVariable(PATH_VARIABLE_SHORT_NAME) String shortName,
                                                                                         @PathVariable(PATH_VARIABLE_VERSION) String version) {
        Optional<MicoApplication> applicationOptional = applicationRepository.findByShortNameAndVersion(shortName, version);
        if (!applicationOptional.isPresent()) {
            return ResponseEntity.notFound().build();
        }
        return applicationOptional.map(application -> new Resource<>(application, getApplicationLinks(application)))
                .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    private List<Resource<MicoApplication>> getApplicationResourceList(List<MicoApplication> applications) {
        return applications.stream().map(application -> new Resource<>(application, getApplicationLinks(application)))
                .collect(Collectors.toList());
    }

    private Iterable<Link> getApplicationLinks(MicoApplication application) {
        LinkedList<Link> links = new LinkedList<>();
        links.add(linkTo(methodOn(ApplicationController.class).getApplicationByShortNameAndVersion(application.getShortName(), application.getVersion().toString())).withSelfRel());
        links.add(linkTo(methodOn(ApplicationController.class).getAllApplications()).withRel("applications"));
        return links;
    }

    @PostMapping
    public ResponseEntity<Resource<MicoApplication>> createApplication(@RequestBody MicoApplication newApplication) {
        Optional<MicoApplication> applicationOptional = applicationRepository.
                findByShortNameAndVersion(newApplication.getShortName(), newApplication.getVersion().toString());
        if (applicationOptional.isPresent()) {
            return ResponseEntity.badRequest().build();
        } else {
            MicoApplication savedApplication = applicationRepository.save(newApplication);

            return ResponseEntity.created(linkTo(methodOn(ApplicationController.class)
                    .getApplicationByShortNameAndVersion(savedApplication.getShortName(), savedApplication.getVersion().toString())).toUri())
                    .body(new Resource<>(savedApplication, getApplicationLinks(savedApplication)));
        }
    }

    @PutMapping("/{" + PATH_VARIABLE_SHORT_NAME + "}/{" + PATH_VARIABLE_VERSION + "}")
    public ResponseEntity<Resource<MicoApplication>> updateApplication(@PathVariable(PATH_VARIABLE_SHORT_NAME) String shortName,
                                                                       @PathVariable(PATH_VARIABLE_VERSION) String version,
                                                                       @RequestBody MicoApplication application) {
        if (!application.getShortName().equals(shortName) || !application.getVersion().equals(version)) {
            return ResponseEntity.badRequest().build();
        }

        Optional<MicoApplication> applicationOptional = applicationRepository.findByShortNameAndVersion(shortName, version);
        if (!applicationOptional.isPresent()) {
            return ResponseEntity.notFound().build();
        }

        application.setId(applicationOptional.get().getId());
        MicoApplication updatedApplication = applicationRepository.save(application);

        return ResponseEntity.ok(new Resource<>(updatedApplication, linkTo(methodOn(ApplicationController.class).updateApplication(shortName, version, application)).withSelfRel()));
    }

    @GetMapping("/{" + PATH_VARIABLE_SHORT_NAME + "}/{" + PATH_VARIABLE_VERSION + "}" + "/deploymentInformation")
    public ResponseEntity<Resource<UiDeploymentInformation>> getApplicationDeploymentInformation(@PathVariable(PATH_VARIABLE_SHORT_NAME) String shortName,
                                                                                         @PathVariable(PATH_VARIABLE_VERSION) String version) {
        Optional<MicoApplication> micoApplicationOptional = applicationRepository.findByShortNameAndVersion(shortName, version);
        if (micoApplicationOptional.isPresent()) {
            ClusterAwarenessFabric8 client = new ClusterAwarenessFabric8();
            KubernetesClient kubernetesClient = client.getClient();
            DeploymentList deploymentList = kubernetesClient.apps().deployments().withLabel(LABLE_APP_KEY, shortName).list();
            if (deploymentList.getItems().size() == 1) {
                Deployment deployment = deploymentList.getItems().get(0);
                UiDeploymentInformation uiDeploymentInformation = new UiDeploymentInformation();
                int requestedReplicas = deployment.getSpec().getReplicas();
                int availableReplicas = deployment.getStatus().getAvailableReplicas();
                uiDeploymentInformation.setAvailableReplicas(availableReplicas);
                uiDeploymentInformation.setRequestedReplicas(requestedReplicas);

                ServiceList serviceList = kubernetesClient.services().withLabel(LABLE_RUN_KEY, shortName).list(); //MicoServiceInterface maps to Service
                List<UiExternalMicoInterfaceInformation> interfacesInformation = new LinkedList<>();
                if (serviceList.getItems().size() >= MINIMAL_EXTERNAL_MICO_INTERFACE_COUNT) {
                    for (Service service : serviceList.getItems()) {
                        String name = service.getMetadata().getName();
                        String ip = service.getSpec().getLoadBalancerIP();
                        UiExternalMicoInterfaceInformation interfaceInformation = UiExternalMicoInterfaceInformation.builder()
                            .name(name).externalIp(ip).build();
                        interfacesInformation.add(interfaceInformation);
                    }
                    uiDeploymentInformation.setInterfacesInformation(interfacesInformation);

                    PodList podList = kubernetesClient.pods().withLabel(LABLE_APP_KEY, shortName).list();
                    List<UiPodInfo> podInfos = new LinkedList<>();
                    for (Pod pod : podList.getItems()) {
                        String name = pod.getSpec().getNodeName();
                        String phase = pod.getStatus().getPhase();
                        String hostIp = pod.getStatus().getHostIP();
                        UiPodInfo uiPodInfo = UiPodInfo.builder().podName(name).phase(phase).hostIp(hostIp).build();
                        podInfos.add(uiPodInfo);
                    }
                    uiDeploymentInformation.setPodInfo(podInfos);
                    return ResponseEntity.ok(new Resource<>(uiDeploymentInformation));
                } else {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "There are not at least " + MINIMAL_EXTERNAL_MICO_INTERFACE_COUNT + " service interface");
                }
            } else {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "The application is running zero or multiple times");
            }
        } else {
            return ResponseEntity.notFound().build();
        }
    }


}
