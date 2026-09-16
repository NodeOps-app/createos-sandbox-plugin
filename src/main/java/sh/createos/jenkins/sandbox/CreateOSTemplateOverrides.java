package sh.createos.jenkins.sandbox;

import java.util.List;

/** Fields a Pipeline caller may use to override an administrator-defined sandbox template. */
interface CreateOSTemplateOverrides {

  String getShape();

  String getRootfs();

  String getRegion();

  default String getRemoteFs() {
    return null;
  }

  int getDiskMiB();

  List<String> getNetworks();

  List<CreateOSDiskAttachment> getDisks();
}
