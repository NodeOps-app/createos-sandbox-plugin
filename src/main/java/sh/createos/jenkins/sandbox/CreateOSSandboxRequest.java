package sh.createos.jenkins.sandbox;

import java.util.List;

/** Immutable inputs for a CreateOS sandbox create request. */
public record CreateOSSandboxRequest(
    String shape,
    String rootfs,
    String region,
    int diskMiB,
    List<String> networkIds,
    List<CreateOSDiskAttachment> disks) {

  /** Copies the collections so a caller cannot mutate a request after it is built. */
  public CreateOSSandboxRequest {
    networkIds = networkIds == null ? List.of() : List.copyOf(networkIds);
    disks = disks == null ? List.of() : List.copyOf(disks);
  }

  /** Builds a sandbox create request from a configured sandbox template. */
  public static CreateOSSandboxRequest fromTemplate(SandboxTemplate template) {
    return new CreateOSSandboxRequest(
        template.getShape(),
        template.getRootfs(),
        template.getRegion(),
        template.getDiskMiB(),
        template.getNetworkIdList(),
        template.getDisks());
  }
}
