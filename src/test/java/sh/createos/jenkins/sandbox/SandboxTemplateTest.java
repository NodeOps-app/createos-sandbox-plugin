package sh.createos.jenkins.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.util.FormValidation;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxTemplateTest {

  private static SandboxTemplate withNetworks(String networks) {
    SandboxTemplate template = new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1");
    template.setNetworks(networks);
    return template;
  }

  @Test
  void networksAreSplitOnBothCommasAndWhitespace() {
    assertEquals(List.of("a", "b", "c"), withNetworks("a, b  c").getNetworkIdList());
  }

  @Test
  void networksDropDuplicatesAndSurroundingBlanks() {
    assertEquals(List.of("a", "b"), withNetworks("  a , b ,, a  ").getNetworkIdList());
  }

  @Test
  void noNetworksMeansAnEmptyListRatherThanAnEmptyEntry() {
    assertTrue(withNetworks("   ").getNetworkIdList().isEmpty());
    assertTrue(withNetworks(null).getNetworkIdList().isEmpty());
  }

  @Test
  void launchMethodDefaultsToInboundForExistingTemplates() {
    SandboxTemplate template = new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1");

    assertEquals(SandboxTemplate.LAUNCH_METHOD_INBOUND, template.getLaunchMethod());
  }

  @Test
  void pipelineOverridesStayAllowedByDefaultForExistingTemplates() {
    SandboxTemplate template = new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1");

    assertTrue(template.isAllowPipelineOverrides());
  }

  @Test
  void unknownLaunchMethodIsRejected() {
    SandboxTemplate template = new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1");

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> template.setLaunchMethod("bogus"));

    assertEquals("Unsupported CreateOS launch method: bogus", thrown.getMessage());
    assertEquals(SandboxTemplate.LAUNCH_METHOD_INBOUND, template.getLaunchMethod());
  }

  @Test
  void launchMethodDescriptorRejectsUnknownValues() {
    SandboxTemplate.DescriptorImpl descriptor = new SandboxTemplate.DescriptorImpl();

    assertEquals(FormValidation.Kind.OK, descriptor.doCheckLaunchMethod("ssh").kind);
    assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckLaunchMethod("bogus").kind);
  }
}
