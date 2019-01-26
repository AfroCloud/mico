package io.github.ust.mico.core;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.github.ust.mico.core.model.*;
import io.github.ust.mico.core.persistence.MicoApplicationRepository;
import io.github.ust.mico.core.persistence.MicoServiceRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.OverrideAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static io.github.ust.mico.core.TestConstants.ID;
import static org.junit.Assert.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Ignore
// TODO Upgrade to JUnit5
@Category(IntegrationTests.class)
@Slf4j
@SpringBootTest
@RunWith(SpringRunner.class)
@AutoConfigureMockMvc
@OverrideAutoConfiguration(enabled = true) //Needed to override our neo4j config
public class DeploymentControllerIntegrationTests {

    private static final String BASE_PATH = "/applications";

    @Autowired
    private ClusterAwarenessFabric8 cluster;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private IntegrationTestsUtils integrationTestsUtils;

    @MockBean
    private MicoServiceRepository serviceRepository;

    @MockBean
    private MicoApplicationRepository applicationRepository;

    private String namespace;
    private MicoService service;
    private MicoApplication application;

    /**
     * Set up everything that is required to execute the integration tests for the deployment.
     */
    @Before
    public void setUp() {
        namespace = integrationTestsUtils.setUpEnvironment();
        log.info("Integration test is running in Kubernetes namespace '{}'", namespace);
        integrationTestsUtils.setUpDockerRegistryConnection(namespace);

        service = getTestService();
        application = getTestApplication(service);

        //serviceRepository.save(service);
        given(serviceRepository.findByShortNameAndVersion(service.getShortName(), service.getVersion())).willReturn(
            Optional.of(service));
        //applicationRepository.save(application);
        given(applicationRepository.findByShortNameAndVersion(application.getShortName(), application.getVersion())).willReturn(
            Optional.of(application));
    }

    /**
     * Delete namespace cleans up everything.
     */
    @After
    public void tearDown() {

        integrationTestsUtils.cleanUpEnvironment(namespace);
    }

    @Test
    public void deployApplicationWithOneService() throws Exception {

        String applicationShortName = application.getShortName();
        String applicationVersion = application.getVersion();
        String expectedDeploymentName = service.getShortName();
        String expectedServiceName = service.getServiceInterfaces().get(0).getServiceInterfaceName();

        mvc.perform(post(BASE_PATH + "/" + applicationShortName + "/" + applicationVersion + "/deploy"))
            .andDo(print())
            .andExpect(status().isOk());

        // Wait until all pods (inclusive build pod) are running or succeeded
        CompletableFuture<Boolean> allPodsInNamespaceAreRunning = integrationTestsUtils.waitUntilAllPodsInNamespaceAreRunning(
            namespace, 10, 1, 60);
        assertTrue("Deployment failed!", allPodsInNamespaceAreRunning.get());

        // Wait until the deployment is created
        CompletableFuture<Deployment> createdDeployment = integrationTestsUtils.waitUntilDeploymentIsCreated(
            expectedDeploymentName, namespace, 1, 1, 10);
        assertNotNull("Kubernetes Deployment was not created!", createdDeployment.get());
        log.debug("Created Kubernetes Deployment: {}", createdDeployment.get().toString());

        // Wait until the service is created
        CompletableFuture<Service> createdService = integrationTestsUtils.waitUntilServiceIsCreated(
            expectedServiceName, namespace, 1, 1, 10);
        assertNotNull("Kubernetes Service was not created!", createdService.get());
        log.debug("Created Kubernetes Service: {}", createdService.get().toString());

        // Assert deployment
        Deployment actualDeployment = cluster.getDeployment(expectedDeploymentName, namespace);
        assertNotNull("No deployment with name '" + expectedDeploymentName + "' exists", actualDeployment);
        assertEquals("Deployment name is not like expected", expectedDeploymentName, actualDeployment.getMetadata().getName());

        // Assert service
        Service actualService = cluster.getService(expectedServiceName, namespace);
        assertNotNull("No service with name '" + expectedServiceName + "' exists", actualService);
        assertEquals("Service name is not like expected", expectedServiceName, actualService.getMetadata().getName());
    }

    private MicoApplication getTestApplication(MicoService service) {
        return MicoApplication.builder()
            .shortName("hello")
            .name("hello-application")
            .version("v1.0.0")
            .description("Hello World Application")
            .deploymentInfo(MicoApplicationDeploymentInfo.builder()
                .serviceDeploymentInfo(service.getId(), MicoServiceDeploymentInfo.builder()
                    .replicas(1)
                    .container(MicoImageContainer.builder()
                        .name("hello-container")
                        .image(service.getShortName())
                        .port(MicoPort.builder()
                            .number(80)
                            .type(MicoPortType.TCP)
                            .build())
                        .build())
                    .build())
                .build())
            .service(service)
            .build();
    }

    private MicoService getTestService() {
        return MicoService.builder()
            .id(ID)
            .shortName("hello")
            .name("UST-MICO/hello")
            .description("Hello World Service")
            .version("v1.0.0")
            .gitCloneUrl("https://github.com/UST-MICO/hello.git")
            .dockerfilePath("Dockerfile")
            .serviceCrawlingOrigin(MicoServiceCrawlingOrigin.valueOf("GITHUB"))
            .serviceInterface(MicoServiceInterface.builder()
                .serviceInterfaceName("hello-service")
                .port(MicoServicePort.builder()
                    .number(80)
                    .targetPort(8080)
                    .type(MicoPortType.TCP)
                    .build())
                .build())
            .build();
    }
}