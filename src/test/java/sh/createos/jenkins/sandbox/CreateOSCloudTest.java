package sh.createos.jenkins.sandbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.util.FormValidation;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class CreateOSCloudTest {

  private static CreateOSCloud cloudWithTemplate(String label) {
    CreateOSCloud cloud = new CreateOSCloud("createos");
    cloud.setTemplates(List.of(new SandboxTemplate(label, "s-1vcpu-1gb", "devbox:1")));
    return cloud;
  }

  @Test
  void canProvisionMatchesOnlyItsOwnLabel(JenkinsRule r) {
    CreateOSCloud cloud = cloudWithTemplate("createos");

    assertTrue(cloud.canProvision(Label.get("createos")));
    assertFalse(cloud.canProvision(Label.get("some-other-label")));
    // `agent any` reaches the cloud with a null Label. CreateOSSlave is EXCLUSIVE, so a sandbox
    // provisioned for it could never accept the job — claiming it would leak one sandbox per
    // provisioner cycle on a controller with zero executors.
    assertFalse(cloud.canProvision((Label) null));
  }

  /**
   * Regression: an unlabelled job (`agent any`) reaches provision() with a null Label.
   * Dereferencing it threw out of provision() into the NodeProvisioner timer and killed the timer
   * task, so every job on the controller stopped being provisioned for — not just the unlabelled
   * one. The only visible symptom was builds stuck on "Waiting for next available executor".
   *
   * <p>The queued build is what makes this test bite: with an empty queue the workload count is
   * zero and provision() returns before reaching any of the null-label paths.
   */
  @Test
  void provisionSurvivesAnUnlabelledQueuedBuild(JenkinsRule r) throws Exception {
    r.jenkins.setNumExecutors(0);
    FreeStyleProject unlabelled = r.createFreeStyleProject();
    unlabelled.scheduleBuild2(0);
    r.jenkins.getQueue().maintain();

    CreateOSCloud cloud = cloudWithTemplate("createos");

    // Cast because Cloud also declares provision(CloudState, int); this plugin overrides the
    // Label overload, which is the one Jenkins reaches with a null Label.
    assertDoesNotThrow(() -> cloud.provision((Label) null, 1));
    assertTrue(cloud.provision((Label) null, 1).isEmpty(), "no sandbox for an unlabelled job");
  }

  @Test
  void templateWithoutLabelIsRejected(JenkinsRule r) {
    SandboxTemplate.DescriptorImpl descriptor =
        r.jenkins.getDescriptorByType(SandboxTemplate.DescriptorImpl.class);

    assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckLabel("").kind);
    assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckLabel(null).kind);
    assertEquals(FormValidation.Kind.OK, descriptor.doCheckLabel("createos").kind);
  }

  @Test
  void declarativeOverridesAreRejectedWhenTemplateDisablesThem(JenkinsRule r) {
    SandboxTemplate template = new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1");
    template.setAllowPipelineOverrides(false);
    CreateOSCloud cloud = new CreateOSCloud("createos");
    cloud.setTemplates(List.of(template));
    r.jenkins.clouds.add(cloud);

    CreateOSDeclarativeAgent agent = new CreateOSDeclarativeAgent("createos");
    agent.setShape("s-8vcpu-8gb");

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> CreateOSPipelineSupport.register(agent, "createos-generated"));

    assertEquals(
        "CreateOS Pipeline-defined overrides are disabled for template: createos",
        thrown.getMessage());
    assertNull(cloud.getTemplateByLabel("createos-generated"));
    assertFalse(cloud.canProvision(Label.get("createos-generated")));
  }

  @Test
  void declarativeAgentWithoutAdminTemplateIsRejected(JenkinsRule r) {
    CreateOSCloud cloud = cloudWithTemplate("createos");
    r.jenkins.clouds.add(cloud);
    CreateOSDeclarativeAgent agent = new CreateOSDeclarativeAgent(null);
    agent.setShape("s-8vcpu-8gb");
    agent.setRootfs("devbox:1");

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> CreateOSPipelineSupport.register(agent, "createos-generated"));

    assertEquals(
        "CreateOS Declarative agents must inherit from an administrator-defined template",
        thrown.getMessage());
    assertNull(cloud.getTemplateByLabel("createos-generated"));
    assertFalse(cloud.canProvision(Label.get("createos-generated")));
  }

  @Test
  void declarativeInheritanceWithoutOverridesStillWorksWhenTemplateDisablesOverrides(
      JenkinsRule r) {
    SandboxTemplate template = new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1");
    template.setAllowPipelineOverrides(false);
    CreateOSCloud cloud = new CreateOSCloud("createos");
    cloud.setTemplates(List.of(template));
    r.jenkins.clouds.add(cloud);

    CreateOSDeclarativeAgent agent = new CreateOSDeclarativeAgent("createos");

    String label = CreateOSPipelineSupport.register(agent, "createos-generated");

    assertEquals("createos-generated", label);
    assertTrue(cloud.canProvision(Label.get("createos-generated")));
  }

  @Test
  void scriptedSandboxOverridesAreRejectedWhenTemplateDisablesThem(JenkinsRule r) {
    SandboxTemplate template = new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1");
    template.setAllowPipelineOverrides(false);
    CreateOSCloud cloud = new CreateOSCloud("createos");
    cloud.setTemplates(List.of(template));
    r.jenkins.clouds.add(cloud);

    CreateOSSandboxStep step = new CreateOSSandboxStep();
    step.setInheritFrom("createos");
    step.setShape("s-8vcpu-8gb");

    IOException thrown =
        assertThrows(IOException.class, () -> CreateOSStepSupport.requestFromStep(step));

    assertEquals(
        "CreateOS Pipeline-defined overrides are disabled for template: createos",
        thrown.getMessage());
  }

  @Test
  void scriptedSandboxCanUseAdminTemplateWithoutOverridesWhenTemplateDisablesOverrides(
      JenkinsRule r) throws Exception {
    SandboxTemplate template = new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1");
    template.setAllowPipelineOverrides(false);
    CreateOSCloud cloud = new CreateOSCloud("createos");
    cloud.setTemplates(List.of(template));
    r.jenkins.clouds.add(cloud);

    CreateOSSandboxStep step = new CreateOSSandboxStep();
    step.setInheritFrom("createos");

    CreateOSSandboxRequest request = CreateOSStepSupport.requestFromStep(step);

    assertEquals("s-1vcpu-1gb", request.shape());
    assertEquals("devbox:1", request.rootfs());
  }
}
