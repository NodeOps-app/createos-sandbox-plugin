package sh.createos.jenkins.sandbox;

import java.io.Serializable;

/** Pipeline body context for exec-mode CreateOS steps. */
public record CreateOSSandboxContext(String sandboxId, String cloudName) implements Serializable {

  private static final long serialVersionUID = 1L;
}
