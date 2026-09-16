package sh.createos.jenkins.sandbox;

import java.util.stream.Collectors;
import jenkins.model.Jenkins;

/** Helper methods called by the trusted declarative Pipeline agent script. */
public final class CreateOSPipelineSupport {

  private CreateOSPipelineSupport() {}

  /** Registers a generated-label template for one Pipeline agent body. */
  public static String register(CreateOSDeclarativeAgent agent, String label) {
    CreateOSCloud cloud = resolveCloud(agent);
    SandboxTemplate base = resolveBaseTemplate(cloud, agent);
    if (base == null) {
      throw new IllegalStateException(
          "CreateOS Declarative agents must inherit from an administrator-defined template");
    }
    if (CreateOSStepSupport.hasTemplateOverrides(agent)) {
      base.rejectPipelineOverrides();
    }
    SandboxTemplate template = mergeTemplate(label, base, agent);
    cloud.registerPipelineTemplate(label, template);
    return label;
  }

  /** Removes a generated-label template after the Pipeline agent body exits. */
  public static void unregister(String label) {
    if (label == null) {
      return;
    }
    for (var cloud : Jenkins.get().clouds) {
      if (cloud instanceof CreateOSCloud createosCloud) {
        createosCloud.unregisterPipelineTemplate(label);
      }
    }
  }

  private static CreateOSCloud resolveCloud(CreateOSDeclarativeAgent agent) {
    Jenkins jenkins = Jenkins.get();
    if (agent.getCloud() != null) {
      var cloud = jenkins.getCloud(agent.getCloud());
      if (cloud instanceof CreateOSCloud createosCloud) {
        return createosCloud;
      }
      throw new IllegalArgumentException("CreateOS cloud not found: " + agent.getCloud());
    }

    if (agent.getInheritFrom() != null) {
      for (var cloud : jenkins.clouds) {
        if (cloud instanceof CreateOSCloud createosCloud
            && createosCloud.getTemplateByLabel(agent.getInheritFrom()) != null) {
          return createosCloud;
        }
      }
      throw new IllegalArgumentException(
          "CreateOS template not found for inheritFrom: " + agent.getInheritFrom());
    }

    for (var cloud : jenkins.clouds) {
      if (cloud instanceof CreateOSCloud createosCloud) {
        return createosCloud;
      }
    }
    throw new IllegalArgumentException("No CreateOS cloud is configured");
  }

  private static SandboxTemplate resolveBaseTemplate(
      CreateOSCloud cloud, CreateOSDeclarativeAgent agent) {
    if (agent.getInheritFrom() == null || agent.getInheritFrom().isBlank()) {
      return null;
    }
    SandboxTemplate base = cloud.getTemplateByLabel(agent.getInheritFrom());
    if (base == null) {
      throw new IllegalArgumentException(
          "CreateOS template not found for inheritFrom: " + agent.getInheritFrom());
    }
    return base;
  }

  private static SandboxTemplate mergeTemplate(
      String label, SandboxTemplate base, CreateOSDeclarativeAgent agent) {
    String shape = CreateOSStepSupport.firstNonBlank(agent.getShape(), base.getShape());
    String rootfs = CreateOSStepSupport.firstNonBlank(agent.getRootfs(), base.getRootfs());

    SandboxTemplate template = base.copyForPipeline(label, shape, rootfs);
    String remoteFs = CreateOSStepSupport.firstNonBlank(agent.getRemoteFs(), null);
    if (remoteFs != null) {
      template.setRemoteFs(remoteFs);
    }
    String region = CreateOSStepSupport.firstNonBlank(agent.getRegion(), null);
    if (region != null) {
      template.setRegion(region);
    }
    if (agent.getDiskMiB() > 0) {
      template.setDiskMiB(agent.getDiskMiB());
    }
    if (!agent.getNetworks().isEmpty()) {
      template.setNetworks(joinNetworks(agent));
    }
    if (!agent.getDisks().isEmpty()) {
      template.setDisks(agent.getDisks());
    }
    return template;
  }

  private static String joinNetworks(CreateOSDeclarativeAgent agent) {
    return agent.getNetworks().stream()
        .filter(network -> network != null && !network.isBlank())
        .map(String::trim)
        .collect(Collectors.joining("\n"));
  }
}
