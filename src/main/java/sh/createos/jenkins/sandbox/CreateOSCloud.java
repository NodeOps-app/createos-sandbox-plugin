package sh.createos.jenkins.sandbox;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/** Jenkins cloud implementation that provisions ephemeral CreateOS sandbox agents. */
public class CreateOSCloud extends Cloud {

  private static final Logger LOGGER = Logger.getLogger(CreateOSCloud.class.getName());

  private String apiUrl;
  private String credentialsId;
  private int containerCap;
  private List<SandboxTemplate> templates;
  private transient Set<String> pendingAgentNames = ConcurrentHashMap.newKeySet();
  private transient ConcurrentHashMap<String, SandboxTemplate> pipelineTemplates =
      new ConcurrentHashMap<>();

  /** Creates a cloud with the default CreateOS API endpoint and capacity. */
  @DataBoundConstructor
  public CreateOSCloud(String name) {
    super(name);
    this.apiUrl = "https://api.sb.createos.sh";
    this.containerCap = 10;
    this.templates = new ArrayList<>();
  }

  // --- Speed up Jenkins NodeProvisioner + cleanup stale agents on startup ---

  /** Applies low-latency Jenkins node provisioner settings during startup. */
  @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED)
  public static void speedUpProvisioner() {
    System.setProperty("hudson.slaves.NodeProvisioner.MARGIN", "50");
    System.setProperty("hudson.slaves.NodeProvisioner.MARGIN0", "0.85");
    System.setProperty("hudson.slaves.NodeProvisioner.initialDelay", "0");
    LOGGER.info("CreateOS: Accelerated NodeProvisioner settings applied");
  }

  /** Terminates CreateOS agents restored from an earlier controller process. */
  @Initializer(after = InitMilestone.SYSTEM_CONFIG_ADAPTED, before = InitMilestone.JOB_LOADED)
  public static void cleanupStaleAgents() {
    List<CreateOSSlave> toRemove = agents().map(CreateOSSlave.class::cast).toList();
    for (CreateOSSlave node : toRemove) {
      try {
        LOGGER.info("Terminating stale agent: " + node.getNodeName());
        node.terminate();
      } catch (Exception e) {
        LOGGER.log(Level.WARNING, "Failed to terminate stale agent", e);
      }
    }
    if (!toRemove.isEmpty()) {
      LOGGER.info("Cleaned up " + toRemove.size() + " stale agent(s)");
    }
  }

  // --- Core Cloud methods ---

  @Override
  public boolean canProvision(Label label) {
    return getTemplateFor(label) != null;
  }

  @Override
  public Collection<PlannedNode> provision(Label label, int excessWorkload) {
    SandboxTemplate template = getTemplateFor(label);
    if (template == null) {
      return Collections.emptyList();
    }

    int currentCount = countCurrentAgents();
    int pendingCount = countPendingAgents();
    if (currentCount + pendingCount >= containerCap) {
      LOGGER.info("Container cap reached (" + containerCap + "), cannot provision more");
      return Collections.emptyList();
    }

    int queuedWorkload = countQueuedWorkload(label);
    int unusedCapacity = countUnusedAgents();
    int neededCapacity = Math.max(0, queuedWorkload - unusedCapacity - pendingCount);
    int toProvision = Math.min(neededCapacity, containerCap - currentCount - pendingCount);
    if (toProvision == 0) {
      return Collections.emptyList();
    }

    LOGGER.info(
        "CreateOS capacity: queued="
            + queuedWorkload
            + ", unused="
            + unusedCapacity
            + ", pending="
            + pendingCount
            + ", provisioning="
            + toProvision);
    List<PlannedNode> planned = new ArrayList<>();

    for (int i = 0; i < toProvision; i++) {
      String agentName =
          "createos-" + template.getLabel() + "-" + System.currentTimeMillis() + "-" + i;
      getPendingAgentNames().add(agentName);

      Future<Node> future =
          Computer.threadPoolForRemoting.submit(
              () -> {
                try {
                  LOGGER.info("Provisioning CreateOS sandbox agent: " + agentName);
                  Node node = new CreateOSSlave(agentName, template, this);

                  // NodeProvisioner does not wake when a PlannedNode future completes.
                  // Defer the review so its current update can first record this future.
                  //
                  // Skipped entirely for a null label, which is what `agent any` produces:
                  // there is no per-label provisioner to nudge, and dereferencing it here
                  // threw an NPE inside this future. Losing the hint only means unlabelled
                  // work waits for the provisioner's next ordinary cycle instead of being
                  // woken early — slower, never stuck.
                  if (label != null) {
                    Timer.get()
                        .schedule(
                            label.nodeProvisioner::suggestReviewNow, 100, TimeUnit.MILLISECONDS);
                  }
                  return node;
                } finally {
                  getPendingAgentNames().remove(agentName);
                }
              });

      planned.add(new PlannedNode(agentName, future, 1));
    }

    return planned;
  }

  // --- Helper methods ---

  private SandboxTemplate getTemplateFor(Label label) {
    SandboxTemplate pipelineTemplate = getPipelineTemplate(label);
    if (pipelineTemplate != null) {
      return pipelineTemplate;
    }
    // A null label is what `agent any` produces. No template serves it: CreateOSSlave is
    // EXCLUSIVE, so a sandbox provisioned for an unlabelled job can never accept it. Matching
    // the first template here made canProvision(null) true, and a controller with zero
    // executors then created a sandbox on every provisioner cycle that nothing could use.
    if (label == null) {
      return null;
    }
    for (SandboxTemplate template : templates) {
      if (label.matches(Label.parse(template.getLabel()))) {
        return template;
      }
    }
    return null;
  }

  private SandboxTemplate getPipelineTemplate(Label label) {
    if (label == null) {
      return null;
    }
    return getPipelineTemplates().get(label.getName());
  }

  SandboxTemplate getTemplateByLabel(String label) {
    if (label == null || label.isBlank()) {
      return null;
    }
    for (SandboxTemplate template : templates) {
      if (label.equals(template.getLabel())) {
        return template;
      }
    }
    return null;
  }

  void registerPipelineTemplate(String label, SandboxTemplate template) {
    getPipelineTemplates().put(label, template);
  }

  void unregisterPipelineTemplate(String label) {
    if (label != null) {
      getPipelineTemplates().remove(label);
    }
  }

  private ConcurrentHashMap<String, SandboxTemplate> getPipelineTemplates() {
    if (pipelineTemplates == null) {
      pipelineTemplates = new ConcurrentHashMap<>();
    }
    return pipelineTemplates;
  }

  // A newly registered node may not be online, connecting, or have a sandbox ID yet. It still
  // represents capacity Jenkins already requested and must count toward the cap.
  private int countCurrentAgents() {
    return (int) agents().count();
  }

  private int countUnusedAgents() {
    return (int)
        agents()
            .map(Node::toComputer)
            .filter(computer -> computer == null || computer.isAcceptingTasks())
            .count();
  }

  private static Stream<Node> agents() {
    return Jenkins.get().getNodes().stream().filter(CreateOSSlave.class::isInstance);
  }

  // Objects.equals and not label.equals: Jenkins passes a null label for work that names
  // no label at all, which is what `agent any` produces. label.equals(...) then threw an
  // NPE out of provision() into the NodeProvisioner timer, killing the timer task — so a
  // single unlabelled job stopped provisioning for every job on the controller, and the
  // only visible symptom was builds sitting on "Waiting for next available executor".
  // Null matches items that carry no assigned label, which is the workload being asked
  // about.
  private int countQueuedWorkload(Label label) {
    return (int)
        Jenkins.get().getQueue().getBuildableItems().stream()
            .filter(item -> Objects.equals(label, item.getAssignedLabel()))
            .count();
  }

  private int countPendingAgents() {
    getPendingAgentNames().removeAll(agents().map(Node::getNodeName).toList());
    return getPendingAgentNames().size();
  }

  private Set<String> getPendingAgentNames() {
    if (pendingAgentNames == null) {
      pendingAgentNames = ConcurrentHashMap.newKeySet();
    }
    return pendingAgentNames;
  }

  void markAgentRegistered(String agentName) {
    getPendingAgentNames().remove(agentName);
  }

  /** Builds an authenticated API client from this cloud's configured Jenkins credential. */
  public CreateOSApiClient buildApiClient() {
    String apiKey = resolveApiKey();
    if (apiKey == null) {
      throw new IllegalStateException(
          "CreateOS API key not found for credential: " + credentialsId);
    }
    return new CreateOSApiClient(apiUrl, apiKey);
  }

  private String resolveApiKey() {
    StringCredentials cred =
        CredentialsMatchers.firstOrNull(
            CredentialsProvider.lookupCredentialsInItemGroup(
                StringCredentials.class, Jenkins.get(), ACL.SYSTEM2),
            CredentialsMatchers.withId(credentialsId));
    return cred != null ? cred.getSecret().getPlainText() : null;
  }

  // --- Getters and setters ---

  public String getApiUrl() {
    return apiUrl;
  }

  @DataBoundSetter
  public void setApiUrl(String apiUrl) {
    this.apiUrl = apiUrl;
  }

  public String getCredentialsId() {
    return credentialsId;
  }

  @DataBoundSetter
  public void setCredentialsId(String credentialsId) {
    this.credentialsId = credentialsId;
  }

  public int getContainerCap() {
    return containerCap;
  }

  @DataBoundSetter
  public void setContainerCap(int containerCap) {
    this.containerCap = containerCap;
  }

  public List<SandboxTemplate> getTemplates() {
    return templates;
  }

  @DataBoundSetter
  public void setTemplates(List<SandboxTemplate> templates) {
    this.templates = templates != null ? templates : new ArrayList<>();
  }

  // --- Descriptor ---

  /** Exposes CreateOS cloud metadata, options, and validation to Jenkins. */
  @Extension
  public static class DescriptorImpl extends Descriptor<Cloud> {

    @Override
    public String getDisplayName() {
      return "CreateOS Sandbox";
    }

    /** Returns the secret-text credentials available to Jenkins administrators. */
    public ListBoxModel doFillCredentialsIdItems() {
      if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
        return new StandardListBoxModel();
      }
      return new StandardListBoxModel()
          .includeEmptyValue()
          .includeMatchingAs(
              ACL.SYSTEM2,
              Jenkins.get(),
              StringCredentials.class,
              Collections.emptyList(),
              CredentialsMatchers.always());
    }

    /** Validates that the configured API endpoint is an HTTP URL. */
    public FormValidation doCheckApiUrl(@QueryParameter String value) {
      if (value == null || value.isBlank()) {
        return FormValidation.error("API URL is required");
      }
      if (!value.startsWith("http://") && !value.startsWith("https://")) {
        return FormValidation.error("Must start with http:// or https://");
      }
      return FormValidation.ok();
    }

    /** Validates access to the configured CreateOS credential. */
    public FormValidation doTestConnection(
        @QueryParameter String apiUrl, @QueryParameter String credentialsId) {
      try {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        StringCredentials cred =
            CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                    StringCredentials.class, Jenkins.get(), ACL.SYSTEM2),
                CredentialsMatchers.withId(credentialsId));
        if (cred == null) {
          return FormValidation.error("Credential not found");
        }
        return FormValidation.ok("Connection successful");
      } catch (Exception e) {
        return FormValidation.error("Failed: " + e.getMessage());
      }
    }
  }
}
