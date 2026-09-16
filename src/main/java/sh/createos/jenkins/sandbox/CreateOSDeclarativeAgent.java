package sh.createos.jenkins.sandbox;

import hudson.Extension;
import hudson.Util;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.DeclarativeAgent;
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.DeclarativeAgentDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/** Declarative Pipeline agent for CreateOS sandboxes. */
public class CreateOSDeclarativeAgent extends DeclarativeAgent<CreateOSDeclarativeAgent>
    implements Serializable, CreateOSTemplateOverrides {

  private static final long serialVersionUID = 1L;

  private String cloud;
  private String inheritFrom;
  private String shape;
  private String rootfs;
  private String region;
  private String remoteFs;
  private int diskMiB;
  private List<String> networks = new ArrayList<>();
  private List<CreateOSDiskAttachment> disks = new ArrayList<>();

  /** Creates a declarative CreateOS agent. */
  @DataBoundConstructor
  public CreateOSDeclarativeAgent(String inheritFrom) {
    this.inheritFrom = Util.fixEmpty(inheritFrom);
  }

  public String getCloud() {
    return cloud;
  }

  @DataBoundSetter
  public void setCloud(String cloud) {
    this.cloud = Util.fixEmpty(cloud);
  }

  public String getInheritFrom() {
    return inheritFrom;
  }

  public String getShape() {
    return shape;
  }

  @DataBoundSetter
  public void setShape(String shape) {
    this.shape = Util.fixEmpty(shape);
  }

  public String getRootfs() {
    return rootfs;
  }

  @DataBoundSetter
  public void setRootfs(String rootfs) {
    this.rootfs = Util.fixEmpty(rootfs);
  }

  public String getRegion() {
    return region;
  }

  @DataBoundSetter
  public void setRegion(String region) {
    this.region = Util.fixEmpty(region);
  }

  public String getRemoteFs() {
    return remoteFs;
  }

  @DataBoundSetter
  public void setRemoteFs(String remoteFs) {
    this.remoteFs = Util.fixEmpty(remoteFs);
  }

  public int getDiskMiB() {
    return diskMiB;
  }

  @DataBoundSetter
  public void setDiskMiB(int diskMiB) {
    this.diskMiB = diskMiB;
  }

  public List<String> getNetworks() {
    return networks;
  }

  @DataBoundSetter
  public void setNetworks(List<String> networks) {
    this.networks = networks != null ? networks : new ArrayList<>();
  }

  public List<CreateOSDiskAttachment> getDisks() {
    return disks;
  }

  @DataBoundSetter
  public void setDisks(List<CreateOSDiskAttachment> disks) {
    this.disks = disks != null ? disks : new ArrayList<>();
  }

  /** Describes the CreateOS declarative Pipeline agent. */
  @Extension(optional = true)
  @Symbol("createos")
  public static class DescriptorImpl extends DeclarativeAgentDescriptor<CreateOSDeclarativeAgent> {

    @Override
    public String getDisplayName() {
      return "CreateOS Sandbox";
    }
  }
}
