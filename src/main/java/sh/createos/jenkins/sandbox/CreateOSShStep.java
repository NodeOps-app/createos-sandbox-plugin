package sh.createos.jenkins.sandbox;

import hudson.AbortException;
import hudson.Extension;
import hudson.model.TaskListener;
import java.io.Serializable;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/** Pipeline step that runs a shell script inside the active CreateOS sandbox. */
public class CreateOSShStep extends Step implements Serializable {

  private static final long serialVersionUID = 1L;

  private final String script;
  private boolean returnStatus;
  private boolean returnStdout;

  /** Creates a shell step with the script to execute inside the sandbox. */
  @DataBoundConstructor
  public CreateOSShStep(String script) {
    this.script = script;
  }

  public String getScript() {
    return script;
  }

  public boolean isReturnStatus() {
    return returnStatus;
  }

  @DataBoundSetter
  public void setReturnStatus(boolean returnStatus) {
    this.returnStatus = returnStatus;
  }

  public boolean isReturnStdout() {
    return returnStdout;
  }

  @DataBoundSetter
  public void setReturnStdout(boolean returnStdout) {
    this.returnStdout = returnStdout;
  }

  @Override
  public StepExecution start(StepContext context) {
    return new Execution(context, this);
  }

  private static class Execution extends SynchronousNonBlockingStepExecution<Object> {

    private static final long serialVersionUID = 1L;

    private final CreateOSShStep step;

    Execution(StepContext context, CreateOSShStep step) {
      super(context);
      this.step = step;
    }

    @Override
    protected Object run() throws Exception {
      CreateOSSandboxContext sandboxContext = getContext().get(CreateOSSandboxContext.class);
      TaskListener listener = getContext().get(TaskListener.class);
      CreateOSCloud cloud = CreateOSStepSupport.resolveCloud(sandboxContext.cloudName());
      CreateOSApiClient.ExecResult result =
          cloud
              .buildApiClient()
              .runShellScript(
                  sandboxContext.sandboxId(),
                  step.getScript(),
                  listener.getLogger(),
                  step.isReturnStdout());

      if (step.isReturnStatus()) {
        return result.exitCode();
      }
      if (result.exitCode() != 0) {
        throw new AbortException("CreateOS shell exited with status " + result.exitCode());
      }
      if (step.isReturnStdout()) {
        return result.stdout();
      }
      return null;
    }
  }

  /** Descriptor for the createosSh Pipeline step. */
  @Extension
  public static class DescriptorImpl extends StepDescriptor {

    @Override
    public String getFunctionName() {
      return "createosSh";
    }

    @Override
    public String getDisplayName() {
      return "Run shell in CreateOS sandbox";
    }

    @Override
    public Set<? extends Class<?>> getRequiredContext() {
      return Set.of(CreateOSSandboxContext.class, TaskListener.class);
    }
  }
}
